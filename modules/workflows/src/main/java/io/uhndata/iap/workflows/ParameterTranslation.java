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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.workflows.FlowNodeTypeInfo.XmlLocation;
import io.uhndata.iap.workflows.ParameterInfo.StructureParameter;
import io.uhndata.iap.workflows.ParameterInfo.ValueParameter;
import io.uhndata.iap.workflows.internal.types.ParameterType;
import io.uhndata.iap.workflows.models.Parameter;

/**
 * Reads the {@link ParameterInfo parameters} a vocabulary entry declares out of the BPMN element it matched. Each
 * is stored on the flow node in the JCR type it was declared with.
 *
 * <p>A parameter's {@code xmlLocation} says where its value is written, see {@link Parameter#getXmlLocation()}. In an
 * extension element a single value is an unprefixed attribute and a list is one IAP-namespaced child per value. An
 * IAP-namespaced attribute of the element itself holds a list comma-joined. A value written the other way round is
 * reported and not read.</p>
 *
 * <p>The {@link ParameterType} named by the parameter's {@code dataType} types the value.</p>
 *
 * <p>Every mistake an author can make is reported, and the translation carries on, because it runs inside a commit.
 * A diagram that says something impossible loses the properties it got wrong, not the save. What is stored
 * anyway is what a reader can still make sense of. A list given too many values keeps them all, while a single
 * value or structure keeps only the first. A value outside the offered ones is kept as written. A value its type
 * cannot convert is dropped, since there is nothing to store it as.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParameterTranslation
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParameterTranslation.class);

    private static final String ID_ATTRIBUTE = "id";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String KEY_DETAIL = "key";

    private static final String VALUE_DETAIL = "value";

    private static final String FLOW_NODE_TYPE_DETAIL = "flowNodeType";

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String MISSING_VALUE_PROBLEM = "a required parameter has no value";

    private ParameterTranslation()
    {
        // Utility class, no instances allowed
    }

    /**
     * Everything a parameter of the element being translated is read with. Only the level changes as a structure
     * is read.
     *
     * @param type the vocabulary entry the element matched
     * @param element the BPMN element being translated
     * @param scope the repository, its node type registry, and where whatever is wrong is reported
     * @param level the flow node being written, or the structure inside it being read
     * @since 0.1.0
     */
    private record Context(FlowNodeTypeInfo type, Element element, ParseScope scope, Level level)
    {
        /** The same element, one structure further down. */
        Context into(final Level inner)
        {
            return new Context(this.type, this.element, this.scope, inner);
        }

        ParseProblems problems()
        {
            return this.scope.problems();
        }

        /** The element values are read from, or {@code null} when the entry declares no extension element. */
        Element extension()
        {
            return this.level.extension();
        }

        /** The node values are stored on. */
        NodeBuilder node()
        {
            return this.level.node();
        }

        /** The path of parameter names and keys down to the level being read, slash-terminated, or empty. */
        String prefix()
        {
            return this.level.prefix();
        }
    }

    /**
     * Where the parameters being read are, and where they go: the flow node itself, or a structure inside it.
     *
     * @param extension the element the values are read from: on the flow node, the entry's extension element, or
     *            {@code null} when the entry declares none; inside a structure, one element of it
     * @param node the node the values are stored on
     * @param prefix what a problem's parameter name starts with: empty on the flow node, and the path of
     *            parameter names and keys down to the structure, slash-terminated, inside one
     * @since 0.1.0
     */
    private record Level(Element extension, NodeBuilder node, String prefix)
    {
    }

    /**
     * How an extension element writes one parameter: as an attribute, or as IAP-namespaced child elements named
     * after it.
     *
     * @param attribute the attribute's value, empty when there is none
     * @param elements the child elements, in diagram order
     * @since 0.1.0
     */
    private record Written(String attribute, List<Element> elements)
    {
    }

    /**
     * Applies every parameter of the matched vocabulary entry to the node being written, having reported whatever
     * the diagram says that none of them reads.
     *
     * @param type the vocabulary entry the element matched
     * @param element the BPMN element being translated
     * @param node the flow node being written
     * @param scope the repository, its node type registry, and where whatever is wrong is reported
     */
    static void applyParameters(final FlowNodeTypeInfo type, final Element element, final NodeBuilder node,
        final ParseScope scope)
    {
        final Element extension = type.xmlExtensionElement() == null ? null
            : WorkflowDefinitionUtils.extensionElement(element, type.xmlExtensionElement());
        final Context context = new Context(type, element, scope, new Level(extension, node, ""));
        UnclaimedMarkup.report(element, type, scope.problems());
        type.parameters().unread().forEach(path -> reportDeclaration(
            "a vocabulary entry holds a parameter of the wrong node type", path, context));
        // Nothing reads such a field, so a structure missing one would otherwise just look short of a field
        type.parameters().misplaced().forEach(path -> reportDeclaration(
            "a vocabulary entry declares a field outside a structure", path, context));
        type.parameters().declared().forEach(parameter -> apply(parameter, context));
    }

    private static void apply(final ParameterInfo parameter, final Context context)
    {
        if (parameter instanceof final ValueParameter value) {
            applyValue(value, context);
        } else {
            applyStructure((StructureParameter) parameter, context);
        }
    }

    private static void applyValue(final ValueParameter parameter, final Context context)
    {
        final List<String> values = read(parameter, context);
        if (values.isEmpty()) {
            // Nothing is written, so the node type's own default stands
            reportIfRequired(parameter, context);
            return;
        }
        validate(parameter, values, context);
        if (parameter.refusal() != null) {
            reportDefect(parameter.refusal(), about(parameter, context), context);
            return;
        }
        final List<Object> converted = convert(parameter, values, context);
        if (converted.isEmpty()) {
            reportIfRequired(parameter, context);
        }
        set(parameter, converted, context);
    }

    /** Reports everything wrong with the values a diagram gave, having decided to store them anyway. */
    private static void validate(final ValueParameter parameter, final List<String> values, final Context context)
    {
        validateCount(parameter, values.size(), context);
        values.stream().filter(value -> !parameter.options().isEmpty() && !parameter.options().contains(value))
            .forEach(value -> report("a parameter has a value it does not offer",
                about(parameter, context).with(VALUE_DETAIL, value), context));
    }

    private static void validateCount(final ParameterInfo parameter, final int count, final Context context)
    {
        if (parameter.maxValues() > 0 && count > parameter.maxValues()) {
            report("a parameter has more values than it accepts", about(parameter, context), context);
        }
        if (count < parameter.minValues()) {
            report("a parameter has fewer values than it requires", about(parameter, context), context);
        }
    }

    private static void reportIfRequired(final ParameterInfo parameter, final Context context)
    {
        if (parameter.isRequired()) {
            report(MISSING_VALUE_PROBLEM, about(parameter, context), context);
        }
    }

    /**
     * Reads a structure: one IAP-namespaced element per value, named after the parameter, whose fields are read
     * from it the way an extension element's parameters are read. A single structure is stored as a child node of
     * the parameter's name. A repeated one is a container of that name holding one node per element, in diagram
     * order.
     */
    private static void applyStructure(final StructureParameter parameter, final Context context)
    {
        if (parameter.location() != XmlLocation.EXTENSION_ELEMENT || context.extension() == null) {
            reportDefect("a structured parameter is not inside an extension element", about(parameter, context),
                context);
            return;
        }
        final List<Element> elements = written(parameter, context).elements();
        if (elements.isEmpty()) {
            reportIfRequired(parameter, context);
            return;
        }
        validateCount(parameter, elements.size(), context);
        if (parameter.refusal() != null) {
            reportDefect(parameter.refusal(), about(parameter, context), context);
            return;
        }
        if (parameter.isMultiValued()) {
            applyRepeated(parameter, elements, context);
            return;
        }
        final Context inner = context.into(new Level(elements.get(0), record(context.node(), parameter.name(),
            parameter), context.prefix() + parameter.name() + "/"));
        reportUnclaimed(parameter, inner);
        fields(inner, parameter.fields());
    }

    /**
     * Stores the elements of a repeated structure in a container, one node per element. Each is named after its
     * key field's value, escaped where a node name cannot hold it, and the key is not stored again as a property.
     * An element with no key, or with the key of an earlier one, is reported and left out.
     */
    private static void applyRepeated(final StructureParameter parameter, final List<Element> elements,
        final Context context)
    {
        if (!parameter.hasSingleValuedKey()) {
            reportDefect("a repeated structured parameter declares no single-valued key", about(parameter, context),
                context);
            return;
        }
        final Map<String, List<Element>> byKey = elements.stream().collect(Collectors.groupingBy(
            element -> element.getAttribute(parameter.keyParameter()).trim(), LinkedHashMap::new,
            Collectors.toList()));
        byKey.getOrDefault("", List.of()).forEach(element -> report(
            "an element of a structured parameter has no key", about(parameter, context), context));
        byKey.remove("");
        byKey.forEach((key, group) -> group.stream().skip(1).forEach(duplicate -> report(
            "an element of a structured parameter has the same key as another",
            about(parameter, context).with(KEY_DETAIL, key), context)));
        final NodeBuilder container = context.node().child(parameter.name());
        container.setProperty(JCR_PRIMARY_TYPE_PROPERTY, OrderedChildren.NT_UNSTRUCTURED_TYPE, Type.NAME);
        final List<ParameterInfo> besideKey = parameter.fields().stream()
            .filter(field -> !field.name().equals(parameter.keyParameter())).toList();
        byKey.forEach((key, group) -> {
            // A key is often a qualified name, such as the property a field sets
            final Context inner = context.into(new Level(group.get(0),
                record(container, Text.escapeIllegalJcrChars(key), parameter),
                context.prefix() + parameter.name() + "/" + key + "/"));
            reportUnclaimed(parameter, inner);
            fields(inner, besideKey);
        });
        OrderedChildren.store(container, byKey.keySet().stream().map(Text::escapeIllegalJcrChars).toList());
    }

    /** The node one element of a structure is stored as, of the structure's node type. */
    private static NodeBuilder record(final NodeBuilder parent, final String name, final StructureParameter parameter)
    {
        final NodeBuilder stored = parent.child(name);
        stored.setProperty(JCR_PRIMARY_TYPE_PROPERTY, parameter.nodeType(), Type.NAME);
        return stored;
    }

    /** Reports what one element of a structure holds that none of its fields reads. */
    private static void reportUnclaimed(final StructureParameter parameter, final Context inner)
    {
        UnclaimedMarkup.reportInside(inner.extension(), parameter.fields().stream().map(ParameterInfo::name).toList(),
            inner.prefix(), inner.element(), inner.type(), inner.problems());
    }

    /**
     * Reads fields of one element of a structure into the node it is stored as. A field can only live inside the
     * element, so one declaring another location is reported and not read.
     */
    private static void fields(final Context inner, final List<ParameterInfo> fields)
    {
        fields.forEach(field -> {
            if (field.location() == XmlLocation.EXTENSION_ELEMENT) {
                apply(field, inner);
            } else {
                reportDefect("a field of a structured parameter is not written inside it", about(field, inner),
                    inner);
            }
        });
    }

    /** What the parameter's type makes of each value, leaving out the ones it drops. */
    private static List<Object> convert(final ValueParameter parameter, final List<String> values,
        final Context context)
    {
        return values.stream()
            .map(value -> parameter.type().convert(value, context.scope().root(),
                problem -> report(problem, about(parameter, context).with(VALUE_DETAIL, value), context)))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * The values a diagram gives a parameter, in the place that parameter says they live.
     *
     * <p>An entry that names no extension element has nowhere but its own element to put a value. A parameter of
     * such an entry that declares another location is reported, and read as an attribute of the element.</p>
     */
    private static List<String> read(final ValueParameter parameter, final Context context)
    {
        if (parameter.location().isBpmn()) {
            return bpmnValues(parameter, context);
        }
        if (parameter.location() == XmlLocation.ATTRIBUTE || context.extension() == null) {
            if (parameter.location() != XmlLocation.ATTRIBUTE) {
                reportDefect("a non-attribute parameter on an entry with no extension element",
                    about(parameter, context), context);
            }
            return attributeValues(parameter,
                context.element().getAttributeNS(WorkflowDefinitionUtils.IAP_NS, parameter.name()));
        }
        final Written written = written(parameter, context);
        return parameter.isMultiValued() ? texts(written.elements()) : attributeValues(parameter, written.attribute());
    }

    /**
     * How the extension element writes a parameter. A structure and a list are read from child elements, and a
     * single value from an attribute. A parameter written only the other way round is reported, and reads as
     * absent.
     */
    private static Written written(final ParameterInfo parameter, final Context context)
    {
        final Written written = new Written(context.extension().getAttribute(parameter.name()),
            WorkflowDefinitionUtils.childElements(context.extension(), WorkflowDefinitionUtils.IAP_NS,
                parameter.name()).toList());
        final boolean readsElements = parameter instanceof StructureParameter || parameter.isMultiValued();
        final boolean onlyAttribute = written.elements().isEmpty() && StringUtils.isNotBlank(written.attribute());
        final boolean onlyElements = !written.elements().isEmpty() && StringUtils.isBlank(written.attribute());
        if (readsElements ? onlyAttribute : onlyElements) {
            report(writtenOtherwiseProblem(parameter), about(parameter, context), context);
        }
        return written;
    }

    private static String writtenOtherwiseProblem(final ParameterInfo parameter)
    {
        if (parameter instanceof StructureParameter) {
            return "a structured parameter is written as an attribute instead";
        }
        return parameter.isMultiValued() ? "a parameter taking a list has it written as an attribute instead"
            : "a single-valued parameter is written as child elements";
    }

    /**
     * The values BPMN itself holds for a parameter, at the end of its path. An unprefixed attribute is read like
     * any other attribute. An element gives its trimmed text, one value per element of the parameter's name, and
     * an expression's body is plain text.
     */
    private static List<String> bpmnValues(final ValueParameter parameter, final Context context)
    {
        final Element holder = WorkflowDefinitionUtils.bpmnElementAt(context.element(), parameter.path());
        if (holder == null) {
            return List.of();
        }
        if (parameter.location() == XmlLocation.BPMN_ATTRIBUTE) {
            return attributeValues(parameter, holder.getAttributeNS(null, parameter.name()));
        }
        return texts(WorkflowDefinitionUtils.childElements(holder, WorkflowDefinitionUtils.BPMN_NS, parameter.name())
            .toList());
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
        // A hand-edited list often ends in a comma, and the blank it leaves is not a value
        return Arrays.stream(raw.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
    }

    /** The trimmed text of each element, blanks dropped. */
    private static List<String> texts(final List<Element> elements)
    {
        return elements.stream().map(element -> element.getTextContent().trim()).filter(StringUtils::isNotBlank)
            .toList();
    }

    /**
     * Stores what survived typing, in the cardinality the parameter declares. Nothing is written when every value
     * was dropped, so a parameter the diagram got wrong keeps the node type's default rather than gaining an empty
     * list.
     */
    private static void set(final ValueParameter parameter, final List<?> values, final Context context)
    {
        if (values.isEmpty()) {
            return;
        }
        final boolean multiple = parameter.isMultiValued();
        final ParameterType type = parameter.type();
        context.node().setProperty(PropertyStates.createProperty(parameter.name(),
            multiple ? values : values.get(0), Type.fromTag(type.storageType(), multiple)));
    }

    /**
     * Where a problem with a parameter of the element being translated lies: the element, and the parameter named
     * by its path from the flow node.
     */
    private static ErrorContext about(final ParameterInfo parameter, final Context context)
    {
        return context.problems().context().with(WorkflowDefinitionUtils.ELEMENT_DETAIL,
            context.element().getAttribute(ID_ATTRIBUTE)).with(PARAMETER_DETAIL, context.prefix() + parameter.name());
    }

    /** Reports a problem the author can fix in the diagram. */
    private static void report(final String problem, final ErrorContext details, final Context context)
    {
        LOGGER.warn("Parameter problem in {}: {} {}", context.problems().path(), problem, details.getDetails());
        context.problems().report(problem, details);
    }

    /** Reports a problem that lies in the vocabulary, which only an administrator can fix. */
    private static void reportDefect(final String problem, final ErrorContext details, final Context context)
    {
        LOGGER.warn("Vocabulary problem in {}: {} {}", context.problems().path(), problem, details.getDetails());
        context.problems().reportDefect(problem, details);
    }

    /** Reports a vocabulary declaration the parse cannot read, for each element of the entry's kind. */
    private static void reportDeclaration(final String problem, final String path, final Context context)
    {
        reportDefect(problem, context.problems().context()
            .with(WorkflowDefinitionUtils.ELEMENT_DETAIL, context.element().getAttribute(ID_ATTRIBUTE))
            .with(FLOW_NODE_TYPE_DETAIL, context.type().name()).with(PARAMETER_DETAIL, path), context);
    }
}
