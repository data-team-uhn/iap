/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.workflows;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

/**
 * Finds the IAP markup on a BPMN element that its vocabulary entry does not read: configuration a diagram gives
 * that the parse then quietly drops.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class UnclaimedMarkup
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UnclaimedMarkup.class);

    private static final String ID_ATTRIBUTE = "id";

    private static final String FLOW_NODE_TYPE_DETAIL = "flowNodeType";

    private static final String EXTENSION_ELEMENT_DETAIL = "extensionElement";

    private static final String ATTRIBUTE_DETAIL = "attribute";

    private static final String CHILD_DETAIL = "child";

    private UnclaimedMarkup()
    {
        // Utility class, no instances allowed
    }

    /**
     * Reports every piece of IAP markup on an element that the entry it matched does not read, since the diagram
     * shows no sign that it was dropped. A misspelled handler element, or one from a module this instance lacks,
     * sends the task to the generic entry. There it parks as a task nobody can complete. An attribute no parameter
     * names, or a value written in the wrong place, is simply lost. Markup in any other namespace is somebody
     * else's and is left alone.
     */
    static void report(final Element element, final FlowNodeTypeInfo match, final ParseProblems problems)
    {
        final Element extensions = WorkflowDefinitionUtils.extensions(element);
        final List<Element> own = extensions == null ? List.of() : iapChildren(extensions)
            .filter(child -> child.getLocalName().equals(match.xmlExtensionElement())).toList();
        if (extensions != null) {
            iapChildren(extensions).filter(child -> !own.contains(child))
                .forEach(child -> reportUnclaimed("an IAP extension element belongs to no matching FlowNodeType",
                    EXTENSION_ELEMENT_DETAIL, child.getLocalName(), element, match, problems));
        }
        // Only the first is read, so a second copy is configuration somebody meant and nothing will see
        if (own.size() > 1) {
            reportUnclaimed("an IAP extension element is written more than once", EXTENSION_ELEMENT_DETAIL,
                match.xmlExtensionElement(), element, match, problems);
        }
        final Set<String> attributes = parameterNames(match, parameter -> readFromElement(parameter, match));
        attributes(element).filter(attribute -> WorkflowDefinitionUtils.IAP_NS.equals(attribute.getNamespaceURI())
            && !attributes.contains(attribute.getLocalName()))
            .forEach(attribute -> reportUnclaimed("an IAP attribute belongs to no parameter of the matching"
                + " FlowNodeType", ATTRIBUTE_DETAIL, attribute.getLocalName(), element, match, problems));
        if (own.isEmpty()) {
            return;
        }
        reportInside(own.get(0), parameterNames(match, parameter -> !readFromElement(parameter, match)), "", element,
            match, problems);
    }

    /**
     * Reports the unprefixed attributes and IAP-namespaced children no parameter is named after. The element
     * checked is an extension element, or one element of a structure inside it.
     *
     * @param holder the element the parameters are read from
     * @param names the names of the parameters read from it
     * @param prefix what each reported name starts with: empty for the extension element, and the path of
     *            parameter names and keys down to a structure, slash-terminated, inside one
     * @param element the BPMN element being translated
     * @param match the vocabulary entry it matched
     * @param problems where whatever is unclaimed is reported
     */
    static void reportInside(final Element holder, final Collection<String> names, final String prefix,
        final Element element, final FlowNodeTypeInfo match, final ParseProblems problems)
    {
        attributes(holder).filter(attribute -> attribute.getNamespaceURI() == null
            && !names.contains(attribute.getLocalName()))
            .forEach(attribute -> reportUnclaimed("an attribute of an IAP extension element belongs to no"
                + " parameter of the matching FlowNodeType", ATTRIBUTE_DETAIL, prefix + attribute.getLocalName(),
                element, match, problems));
        iapChildren(holder).filter(child -> !names.contains(child.getLocalName()))
            .forEach(child -> reportUnclaimed("a child of an IAP extension element belongs to no parameter of the"
                + " matching FlowNodeType", CHILD_DETAIL, prefix + child.getLocalName(), element, match, problems));
    }

    private static Stream<Element> iapChildren(final Element element)
    {
        return WorkflowDefinitionUtils.childElements(element, WorkflowDefinitionUtils.IAP_NS);
    }

    /**
     * Whether a parameter is read from the element's own attributes rather than from its extension element. An
     * entry naming no extension element reads every parameter of either kind from the element itself.
     */
    private static boolean readFromElement(final FlowNodeTypeInfo.ParameterInfo parameter,
        final FlowNodeTypeInfo match)
    {
        return parameter.location() == FlowNodeTypeInfo.XmlLocation.ATTRIBUTE || match.xmlExtensionElement() == null;
    }

    /** The names of the entry's parameters written in IAP markup that are read from where {@code readHere} says. */
    private static Set<String> parameterNames(final FlowNodeTypeInfo match,
        final Predicate<FlowNodeTypeInfo.ParameterInfo> readHere)
    {
        return match.parameters().declared().stream()
            .filter(parameter -> !parameter.location().isBpmn())
            .filter(readHere)
            .map(FlowNodeTypeInfo.ParameterInfo::name)
            .collect(Collectors.toSet());
    }

    private static Stream<Attr> attributes(final Element element)
    {
        final NamedNodeMap attributes = element.getAttributes();
        return IntStream.range(0, attributes.getLength()).mapToObj(attributes::item).map(Attr.class::cast);
    }

    private static void reportUnclaimed(final String problem, final String kind, final String name,
        final Element element, final FlowNodeTypeInfo match, final ParseProblems problems)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        LOGGER.warn("BPMN element {} in {}: {}: {} {} (FlowNodeType {})", id, problems.path(), problem, kind, name,
            match.name());
        problems.report(problem, problems.context().with(WorkflowDefinitionUtils.ELEMENT_DETAIL, id).with(kind, name)
            .with(FLOW_NODE_TYPE_DETAIL, match.name()));
    }
}
