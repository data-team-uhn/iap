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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import io.uhndata.iap.workflows.ParameterInfo.ValueParameter;

/**
 * Finds the markup on a BPMN element that its vocabulary entry does not read: everything a diagram can say that the
 * parse then quietly drops. One is made for each element translated.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class UnclaimedMarkup
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UnclaimedMarkup.class);

    private static final String EXTENSION_ELEMENT_DETAIL = "extensionElement";

    private static final String ATTRIBUTE_DETAIL = "attribute";

    private static final String CHILD_DETAIL = "child";

    private static final Wording BPMN_WORDING = new Wording("a BPMN attribute is read by no parameter of its entry",
        "a BPMN element is read by no parameter of its entry");

    private static final Wording IAP_WORDING = new Wording(
        "an IAP extension attribute is read by no parameter of its entry",
        "an IAP extension child is read by no parameter of its entry");

    private final Element element;

    private final FlowNodeTypeInfo match;

    private final ParseProblems problems;

    /**
     * What a problem says about an attribute, and about a child element, that nothing reads.
     *
     * @param attribute the problem for an attribute
     * @param child the problem for a child element
     * @since 0.1.0
     */
    private record Wording(String attribute, String child)
    {
    }

    /**
     * The attributes and child elements one element may carry: what the parse reads itself, and what the entry's
     * parameters read there.
     *
     * @param attributes the names of the attributes
     * @param children the local names of the child elements
     * @since 0.1.0
     */
    private record Allowed(Set<String> attributes, Set<String> children)
    {
        static Allowed of(final Set<String> attributes, final Set<String> children)
        {
            return new Allowed(new HashSet<>(attributes), new HashSet<>(children));
        }
    }

    /**
     * Prepares to report on one element.
     *
     * @param element the BPMN element being translated
     * @param match the vocabulary entry it matched
     * @param problems where whatever is unclaimed is reported
     */
    UnclaimedMarkup(final Element element, final FlowNodeTypeInfo match, final ParseProblems problems)
    {
        this.element = element;
        this.match = match;
        this.problems = problems;
    }

    /**
     * Reports what the element carries that its entry does not read: BPMN markup, IAP extension elements other than
     * the entry's own, a second copy of the entry's own, IAP attributes no parameter names, and what the entry's own
     * extension element holds that no parameter names. Markup in any other namespace is left alone.
     *
     * @param own the entry's extension element, the one its parameters are read from, or {@code null} when the
     *            element has none
     */
    void report(final Element own)
    {
        reportBpmn();
        reportOtherExtensions();
        reportRepeatedExtension();
        reportUnreadIapAttributes();
        if (own != null) {
            reportInside(own, parameterNames(parameter -> !readFromElement(parameter)), "");
        }
    }

    /**
     * Reports the attributes and IAP-namespaced children no parameter is named after, on an extension element or on
     * one element of a structure inside it. An IAP-prefixed attribute is reported too: the parser reads only
     * unprefixed ones there.
     *
     * @param holder the element the parameters are read from
     * @param names the names of the parameters read from it
     * @param prefix what each reported name starts with: empty for the extension element, and the path of
     *            parameter names and keys down to a structure, slash-terminated, inside one
     */
    void reportInside(final Element holder, final Collection<String> names, final String prefix)
    {
        final Set<String> allowed = Set.copyOf(names);
        reportBeyond(holder, prefix, new Allowed(allowed, allowed), WorkflowDefinitionUtils.IAP_NS, IAP_WORDING);
        attributes(holder).filter(attribute -> WorkflowDefinitionUtils.IAP_NS.equals(attribute.getNamespaceURI()))
            .forEach(attribute -> reportUnclaimed("an IAP extension attribute is written with a prefix, which is"
                + " not read there", ATTRIBUTE_DETAIL, prefix + attribute.getLocalName()));
    }

    /**
     * Reports the BPMN markup neither the entry's parameters nor the parse itself read, on the element and on every
     * element along a path the parameters or the parse lead down. Only the first element of a name is read at each
     * step, so a second one is reported as well.
     */
    private void reportBpmn()
    {
        allowedMarkup().forEach((path, allowed) -> {
            final List<Element> reached = reached(path);
            final String prefix = path.isEmpty() ? "" : String.join("/", path) + "/";
            reached.forEach(at -> reportBeyond(at, prefix, allowed, WorkflowDefinitionUtils.BPMN_NS, BPMN_WORDING));
            if (reached.size() > 1) {
                reportUnclaimed("a BPMN element is written more than once", CHILD_DETAIL, String.join("/", path));
            }
        });
    }

    /**
     * What each element may carry, keyed by the path of BPMN child names leading to it from the flow node: what the
     * parse reads there itself, then each step and end of the entry's parameters' paths, and the child element the
     * entry is matched by.
     */
    private Map<List<String>, Allowed> allowedMarkup()
    {
        final Map<List<String>, Allowed> allowed = new LinkedHashMap<>();
        allowed.put(List.of(), Allowed.of(WorkflowDefinitionUtils.STRUCTURAL_ATTRIBUTES,
            WorkflowDefinitionUtils.STRUCTURAL_CHILDREN));
        WorkflowDefinitionUtils.STRUCTURAL_BELOW.forEach((child, attributes) ->
            allowed.put(List.of(child), Allowed.of(attributes, WorkflowDefinitionUtils.COMMON_CHILDREN)));
        if (this.match.xmlChildElement() != null) {
            claimChild(allowed, List.of(), this.match.xmlChildElement());
        }
        bpmnValues().forEach(parameter -> {
            final List<String> path = parameter.path();
            IntStream.range(0, path.size()).forEach(step -> claimChild(allowed, path.subList(0, step), path.get(step)));
            final Allowed end = allowedAt(allowed, path);
            (parameter.location() == FlowNodeTypeInfo.XmlLocation.BPMN_ATTRIBUTE ? end.attributes() : end.children())
                .add(parameter.name());
        });
        return allowed;
    }

    private static void claimChild(final Map<List<String>, Allowed> allowed, final List<String> path,
        final String child)
    {
        allowedAt(allowed, path).children().add(child);
        allowedAt(allowed, Stream.concat(path.stream(), Stream.of(child)).toList());
    }

    private static Allowed allowedAt(final Map<List<String>, Allowed> allowed, final List<String> path)
    {
        return allowed.computeIfAbsent(List.copyOf(path),
            key -> Allowed.of(WorkflowDefinitionUtils.COMMON_ATTRIBUTES, WorkflowDefinitionUtils.COMMON_CHILDREN));
    }

    /** Every element at the end of a path of BPMN children: a step takes all the children of its name. */
    private List<Element> reached(final List<String> path)
    {
        List<Element> reached = List.of(this.element);
        for (final String step : path) {
            reached = reached.stream()
                .flatMap(at -> WorkflowDefinitionUtils.childElements(at, WorkflowDefinitionUtils.BPMN_NS, step))
                .toList();
        }
        return reached;
    }

    /** Reports the IAP extension elements other than the entry's own, which nothing reads. */
    private void reportOtherExtensions()
    {
        final Element extensions = WorkflowDefinitionUtils.extensions(this.element);
        if (extensions == null) {
            return;
        }
        WorkflowDefinitionUtils.childElements(extensions, WorkflowDefinitionUtils.IAP_NS)
            .filter(child -> !child.getLocalName().equals(this.match.xmlExtensionElement()))
            .forEach(child -> reportUnclaimed("an IAP extension element is not read, since the element matched"
                + " another entry", EXTENSION_ELEMENT_DETAIL, child.getLocalName()));
    }

    /** Reports a second copy of the entry's own extension element: only the first is read. */
    private void reportRepeatedExtension()
    {
        final Element extensions = WorkflowDefinitionUtils.extensions(this.element);
        final String own = this.match.xmlExtensionElement();
        if (extensions != null && own != null
            && WorkflowDefinitionUtils.childElements(extensions, WorkflowDefinitionUtils.IAP_NS, own).count() > 1) {
            reportUnclaimed("an IAP extension element is written more than once", EXTENSION_ELEMENT_DETAIL, own);
        }
    }

    /** Reports the IAP attributes of the element that no parameter read from the element names. */
    private void reportUnreadIapAttributes()
    {
        final Set<String> names = parameterNames(this::readFromElement);
        attributes(this.element)
            .filter(attribute -> WorkflowDefinitionUtils.IAP_NS.equals(attribute.getNamespaceURI())
                && !names.contains(attribute.getLocalName()))
            .forEach(attribute -> reportUnclaimed("an IAP attribute is read by no parameter of its entry",
                ATTRIBUTE_DETAIL, attribute.getLocalName()));
    }

    /** Reports the unprefixed attributes, and the children of one namespace, that one element may not carry. */
    private void reportBeyond(final Element holder, final String prefix, final Allowed allowed,
        final String childNamespace, final Wording wording)
    {
        attributes(holder).filter(attribute -> attribute.getNamespaceURI() == null
            && !allowed.attributes().contains(attribute.getLocalName()))
            .forEach(attribute -> reportUnclaimed(wording.attribute(), ATTRIBUTE_DETAIL,
                prefix + attribute.getLocalName()));
        WorkflowDefinitionUtils.childElements(holder, childNamespace)
            .filter(child -> !allowed.children().contains(child.getLocalName()))
            .forEach(child -> reportUnclaimed(wording.child(), CHILD_DETAIL, prefix + child.getLocalName()));
    }

    /** The parameters of the entry that read a value out of BPMN's own markup. A structure never can. */
    private Stream<ValueParameter> bpmnValues()
    {
        return this.match.parameters().declared().stream().filter(ValueParameter.class::isInstance)
            .map(ValueParameter.class::cast).filter(parameter -> parameter.location().isBpmn());
    }

    /**
     * Whether a parameter is read from the element's own attributes rather than from its extension element. An
     * entry naming no extension element reads every parameter of either kind from the element itself.
     */
    private boolean readFromElement(final ParameterInfo parameter)
    {
        return parameter.location() == FlowNodeTypeInfo.XmlLocation.ATTRIBUTE
            || this.match.xmlExtensionElement() == null;
    }

    /** The names of the entry's parameters written in IAP markup that are read from where {@code readHere} says. */
    private Set<String> parameterNames(final Predicate<ParameterInfo> readHere)
    {
        return this.match.parameters().declared().stream()
            .filter(parameter -> !parameter.location().isBpmn())
            .filter(readHere)
            .map(ParameterInfo::name)
            .collect(Collectors.toSet());
    }

    private static Stream<Attr> attributes(final Element element)
    {
        final NamedNodeMap attributes = element.getAttributes();
        return IntStream.range(0, attributes.getLength()).mapToObj(attributes::item).map(Attr.class::cast);
    }

    private void reportUnclaimed(final String problem, final String kind, final String name)
    {
        final String id = this.element.getAttribute(WorkflowDefinitionUtils.ID_ATTRIBUTE);
        LOGGER.warn("BPMN element {} in {}: {}: {} {} (FlowNodeType {})", id, this.problems.path(), problem, kind,
            name, this.match.name());
        this.problems.report(problem, this.problems.context().with(WorkflowDefinitionUtils.ELEMENT_DETAIL, id)
            .with(kind, name).with(WorkflowDefinitionUtils.FLOW_NODE_TYPE_DETAIL, this.match.name()));
    }
}
