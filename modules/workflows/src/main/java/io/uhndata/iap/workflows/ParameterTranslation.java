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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import io.uhndata.iap.workflows.FlowNodeTypeInfo.ParameterInfo;
import io.uhndata.iap.workflows.FlowNodeTypeInfo.XmlLocation;
import io.uhndata.iap.workflows.internal.types.BooleanParameterType;
import io.uhndata.iap.workflows.internal.types.LongParameterType;
import io.uhndata.iap.workflows.internal.types.ParameterType;
import io.uhndata.iap.workflows.internal.types.PrincipalParameterType;
import io.uhndata.iap.workflows.internal.types.ReferenceParameterType;
import io.uhndata.iap.workflows.internal.types.TextParameterType;
import io.uhndata.iap.workflows.models.Parameter;

/**
 * Reads the {@link ParameterInfo parameters} a vocabulary entry declares out of the BPMN element it matched. Each
 * is stored on the flow node in the JCR type it was declared with.
 *
 * <p><strong>Where a value is written</strong> is the parameter's own choice, because the cases are genuinely
 * different. A property the node <em>kind</em> declares, such as who may pass through it, is an IAP-namespaced
 * attribute of the element itself. The element need carry no extension at all for it. A list is comma-joined there,
 * marked in the parameter rather than guessed, because a value containing a comma must not become two values by
 * accident. A value BPMN has a word for is wherever BPMN puts it. That is one of its own attributes or the text of
 * one of its elements, on the element or along a path of its children. Everything else lives inside the entry's
 * extension element, where one service task handler's configuration cannot be confused with another's on the same
 * {@code bpmn:serviceTask}. There a single value is an unprefixed attribute of that element, and a list is one
 * IAP-namespaced child per value.</p>
 *
 * <p><strong>How a value is typed</strong> is up to the {@link ParameterType} the parameter's {@code dataType}
 * names.</p>
 *
 * <p>Every violation an author can commit is reported and then survived, because this runs inside a commit. A
 * diagram that says something impossible costs the properties it got wrong rather than the save. What is stored
 * anyway is what a reader can still make sense of. A list given too many values keeps them all, while a single
 * value or structure keeps only the first. A value outside the offered ones is kept as written. A value its type
 * cannot convert is dropped, since there is nothing to store it as. A list written as an attribute of the extension
 * element, or a single value written as its children, is reported and <em>not</em> read. Guessing which a diagram
 * meant would make where a value lives a matter of opinion.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParameterTranslation
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParameterTranslation.class);

    /** The parameter types, by the {@code dataType} that names them. */
    private static final Map<String, ParameterType> TYPES = Map.of(
        Parameter.TYPE_TEXT, new TextParameterType(),
        Parameter.TYPE_BOOLEAN, new BooleanParameterType(),
        Parameter.TYPE_LONG, new LongParameterType(),
        Parameter.TYPE_REFERENCE, new ReferenceParameterType(),
        Parameter.TYPE_PRINCIPAL, new PrincipalParameterType());

    private static final String ID_ATTRIBUTE = "id";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String NAMED_PROPERTY_DEFINITIONS = "rep:namedPropertyDefinitions";

    private ParameterTranslation()
    {
        // Utility class, no instances allowed
    }

    /**
     * What does not change from one parameter of the same element to the next, bundled together so the individual
     * methods stay within the checkstyle parameter limit.
     *
     * @param type the vocabulary entry the element matched
     * @param element the BPMN element being translated
     * @param extension the entry's extension element, or {@code null} when the entry declares none
     * @param node the flow node being written
     * @param root the committed repository root, which a parameter type may look values up in
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state
     * @param problems where whatever is wrong is reported
     * @since 0.1.0
     */
    private record Context(FlowNodeTypeInfo type, Element element, Element extension, NodeBuilder node,
        NodeState root, NodeState nodeTypesRoot, ParseProblems problems)
    {
    }

    /**
     * Applies every parameter of the matched vocabulary entry to the node being written.
     *
     * @param type the vocabulary entry the element matched
     * @param element the BPMN element being translated
     * @param node the flow node being written
     * @param root the committed repository root, which a parameter type may look values up in
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state
     * @param problems where whatever is wrong is reported
     */
    static void applyParameters(final FlowNodeTypeInfo type, final Element element, final NodeBuilder node,
        final NodeState root, final NodeState nodeTypesRoot, final ParseProblems problems)
    {
        final Element extension = type.xmlExtensionElement() == null ? null
            : WorkflowDefinitionUtils.extensionElement(element, type.xmlExtensionElement());
        final Context context = new Context(type, element, extension, node, root, nodeTypesRoot, problems);
        // The catalogue lists these by their resource type, so an author can give one a value nothing stores
        type.parameters().unread().forEach(name -> problems.report(
            "a vocabulary entry holds a parameter that is not a wf:Parameter node", problems.context()
                .with(WorkflowDefinitionUtils.ELEMENT_DETAIL, element.getAttribute(ID_ATTRIBUTE))
                .with("flowNodeType", type.name()).with(PARAMETER_DETAIL, name)));
        type.parameters().declared().forEach(parameter -> apply(parameter, context));
    }

    private static void apply(final ParameterInfo parameter, final Context context)
    {
        final List<String> values = read(parameter, context);
        if (values.isEmpty()) {
            if (parameter.isRequired()) {
                report("a required parameter has no value", parameter, context);
            }
            // Nothing is written rather than something empty, so that the node type's own default stands: a
            // diagram that said nothing must not overwrite it with nothing.
            return;
        }
        // The node type constrains dataType to the names this map holds
        final ParameterType type = TYPES.get(parameter.dataType());
        validate(parameter, values, context);
        if (disagreesWithNodeType(parameter, type.storageType(), context)) {
            report("a parameter is declared differently by the node type it is stored in", parameter, context);
            return;
        }
        final List<Object> converted = convert(type, parameter, values, context);
        if (converted.isEmpty() && parameter.isRequired()) {
            report("a required parameter has no value", parameter, context);
        }
        set(parameter, converted, type.storageType(), context);
    }

    /** Reports everything wrong with the values a diagram gave, having decided to store them anyway. */
    private static void validate(final ParameterInfo parameter, final List<String> values, final Context context)
    {
        if (parameter.maxValues() > 0 && values.size() > parameter.maxValues()) {
            report("a parameter has more values than it accepts", parameter, context);
        }
        if (values.size() < parameter.minValues()) {
            report("a parameter has fewer values than it requires", parameter, context);
        }
        for (final String value : values) {
            if (!parameter.options().isEmpty() && !parameter.options().contains(value)) {
                report("a parameter has a value it does not offer", parameter, context);
            }
        }
    }

    /** What the parameter's type makes of each value, leaving out the ones it drops. */
    private static List<Object> convert(final ParameterType type, final ParameterInfo parameter,
        final List<String> values, final Context context)
    {
        final List<Object> converted = new ArrayList<>();
        for (final String value : values) {
            final Object typed = type.convert(value, parameter.declaration(), context.root(),
                problem -> report(problem, parameter, context));
            if (typed != null) {
                converted.add(typed);
            }
        }
        return converted;
    }

    /**
     * The values a diagram gives a parameter, in the place that parameter says they live.
     *
     * <p>An entry that names no extension element has nowhere but its own element to put a value. A parameter of
     * such an entry claiming otherwise is reported and read as an attribute, since otherwise nothing could store
     * it.</p>
     */
    private static List<String> read(final ParameterInfo parameter, final Context context)
    {
        if (parameter.location().isBpmn()) {
            return bpmnValues(parameter, context);
        }
        if (parameter.location() == XmlLocation.ATTRIBUTE || context.extension() == null) {
            if (parameter.location() != XmlLocation.ATTRIBUTE) {
                report("a parameter is not an attribute of an entry that declares no extension element", parameter,
                    context);
            }
            return attributeValues(parameter,
                context.element().getAttributeNS(WorkflowDefinitionUtils.IAP_NS, parameter.name()));
        }
        if (parameter.isMultiValued()) {
            return childValues(parameter, context);
        }
        final List<String> values = attributeValues(parameter, context.extension().getAttribute(parameter.name()));
        if (values.isEmpty()
            && !children(context.extension(), WorkflowDefinitionUtils.IAP_NS, parameter.name()).isEmpty()) {
            report("a parameter taking one value has it written as child elements instead", parameter, context);
        }
        return values;
    }

    /**
     * The values BPMN itself holds for a parameter, at the end of its path. An unprefixed attribute is read like
     * any other attribute. An element gives its trimmed text, one value per element of the parameter's name, and
     * an expression's body is plain text.
     */
    private static List<String> bpmnValues(final ParameterInfo parameter, final Context context)
    {
        final Element holder = WorkflowDefinitionUtils.bpmnElementAt(context.element(), parameter.path());
        if (holder == null) {
            return List.of();
        }
        if (parameter.location() == XmlLocation.BPMN_ATTRIBUTE) {
            return attributeValues(parameter, holder.getAttributeNS(null, parameter.name()));
        }
        return children(holder, WorkflowDefinitionUtils.BPMN_NS, parameter.name());
    }

    /** The parameter's IAP-namespaced children of the extension element, or a report of the value written wrong. */
    private static List<String> childValues(final ParameterInfo parameter, final Context context)
    {
        final List<String> values = children(context.extension(), WorkflowDefinitionUtils.IAP_NS, parameter.name());
        if (values.isEmpty() && StringUtils.isNotBlank(context.extension().getAttribute(parameter.name()))) {
            report("a parameter taking a list has it written as an attribute instead", parameter, context);
        }
        return values;
    }

    /**
     * One attribute read as the parameter's cardinality says to read it. A list is split on commas and each part
     * trimmed. A single value is trimmed but otherwise taken as written, commas and all.
     */
    private static List<String> attributeValues(final ParameterInfo parameter, final String raw)
    {
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        if (!parameter.isMultiValued()) {
            return List.of(raw.trim());
        }
        // A trailing separator is the commonest way a hand-edited list goes wrong, and an empty value is a
        // principal nothing can match rather than a harmless blank.
        return Arrays.stream(raw.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
    }

    /** The trimmed text of each child of an element with this name in this namespace, blanks dropped. */
    private static List<String> children(final Element element, final String namespace, final String name)
    {
        return WorkflowDefinitionUtils.childElements(element, namespace, name)
            .map(child -> child.getTextContent().trim())
            .filter(StringUtils::isNotBlank)
            .toList();
    }

    /**
     * Stores what survived typing, in the cardinality the parameter declares. Nothing is written when every value
     * was dropped, so a parameter the diagram got wrong keeps the node type's default rather than gaining an empty
     * list.
     */
    private static void set(final ParameterInfo parameter, final List<?> values, final int storageType,
        final Context context)
    {
        if (values.isEmpty()) {
            return;
        }
        final boolean multiple = parameter.isMultiValued();
        context.node().setProperty(PropertyStates.createProperty(parameter.name(),
            multiple ? values : values.get(0), Type.fromTag(storageType, multiple)));
    }

    /**
     * Whether the node type a parameter is stored in declares a property of the same name in another type or
     * cardinality. Oak would not refuse the value: a mismatched definition that is not mandatory gives way to the
     * residual ones every flow node has, leaving a property in a shape its own node type rules out. The vocabulary
     * entry is what is wrong, so the value is not written.
     *
     * <p>Asked the way Oak asks it, of the definitions it flattens onto each registered type. A match is keyed by
     * the value's own type, or by {@code UNDEFINED}, in the same cardinality.</p>
     */
    private static boolean disagreesWithNodeType(final ParameterInfo parameter, final int storageType,
        final Context context)
    {
        final NodeState definitions = context.nodeTypesRoot().getChildNode(context.type().jcrNodeType())
            .getChildNode(NAMED_PROPERTY_DEFINITIONS).getChildNode(parameter.name());
        if (!definitions.exists()) {
            return false;
        }
        final boolean multiple = parameter.isMultiValued();
        return !definitions.hasChildNode(Type.fromTag(storageType, multiple).toString())
            && !definitions.hasChildNode(Type.fromTag(PropertyType.UNDEFINED, multiple).toString());
    }

    /** Reports a problem with a parameter of the element being translated. */
    private static void report(final String problem, final ParameterInfo parameter, final Context context)
    {
        final String id = context.element().getAttribute(ID_ATTRIBUTE);
        LOGGER.warn("Parameter {} of element {} in {}: {}", parameter.name(), id, context.problems().path(),
            problem);
        context.problems().report(problem, context.problems().context()
            .with(WorkflowDefinitionUtils.ELEMENT_DETAIL, id).with(PARAMETER_DETAIL, parameter.name()));
    }
}
