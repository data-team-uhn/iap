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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.jcr.PropertyType;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.util.Text;
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
    private static final Map<String, ParameterType> PARAMETER_TYPES = Map.of(
        Parameter.TYPE_TEXT, new TextParameterType(),
        Parameter.TYPE_BOOLEAN, new BooleanParameterType(),
        Parameter.TYPE_LONG, new LongParameterType(),
        Parameter.TYPE_REFERENCE, new ReferenceParameterType(),
        Parameter.TYPE_PRINCIPAL, new PrincipalParameterType());

    private static final String ID_ATTRIBUTE = "id";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String NAMED_PROPERTY_DEFINITIONS = "rep:namedPropertyDefinitions";

    private static final String RESIDUAL_PROPERTY_DEFINITIONS = "rep:residualPropertyDefinitions";

    private static final String NAMED_CHILD_NODE_DEFINITIONS = "rep:namedChildNodeDefinitions";

    private static final String RESIDUAL_CHILD_NODE_DEFINITIONS = "rep:residualChildNodeDefinitions";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String IS_ABSTRACT = "jcr:isAbstract";

    private static final String IS_MIXIN = "jcr:isMixin";

    private static final String MANDATORY_PROPERTIES = "rep:mandatoryProperties";

    private static final String MANDATORY_CHILD_NODES = "rep:mandatoryChildNodes";

    private static final String DECLARED_DIFFERENTLY =
        "a parameter is declared differently by the node type it is stored in";

    private static final String NOT_ALLOWED = "a parameter is not allowed by the node type it is stored in";

    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String KEY_DETAIL = "key";

    private static final String NT_UNSTRUCTURED = "nt:unstructured";

    private static final String CHILD_ORDER = ":childOrder";

    private ParameterTranslation()
    {
        // Utility class, no instances allowed
    }

    /**
     * Everything a parameter of the element being translated is read with, bundled so the individual methods stay
     * within the checkstyle parameter limit. Only the level changes as a structure is read.
     *
     * @param type the vocabulary entry the element matched
     * @param element the BPMN element being translated
     * @param root the committed repository root, which a parameter type may look values up in
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state
     * @param problems where whatever is wrong is reported
     * @param level the flow node being written, or the structure inside it being read
     * @since 0.1.0
     */
    private record Context(FlowNodeTypeInfo type, Element element, NodeState root, NodeState nodeTypesRoot,
        ParseProblems problems, Level level)
    {
        /** The same element, one structure further down. */
        Context into(final Level inner)
        {
            return new Context(this.type, this.element, this.root, this.nodeTypesRoot, this.problems, inner);
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
    }

    /**
     * Where the parameters being read are, and where they go: the flow node itself, or a structure inside it.
     *
     * @param extension the element the values are read from: on the flow node, the entry's extension element, or
     *            {@code null} when the entry declares none; inside a structure, one element of it
     * @param node the node the values are stored on
     * @param nodeType that node's type
     * @param prefix what a problem's parameter name starts with: empty on the flow node, and the path of
     *            parameter names and keys down to the structure, slash-terminated, inside one
     * @since 0.1.0
     */
    private record Level(Element extension, NodeBuilder node, String nodeType, String prefix)
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
        final Context context = new Context(type, element, root, nodeTypesRoot, problems,
            new Level(extension, node, type.jcrNodeType(), ""));
        type.parameters().unread().forEach(path -> reportDeclaration(
            "a vocabulary entry holds a parameter that is not a wf:Parameter node", path, type, element, problems));
        // Nothing reads such a field, so a structure missing one would otherwise just look short of a field
        type.parameters().misplaced().forEach(path -> reportDeclaration(
            "a vocabulary entry declares a field outside its structure's parameters node", path, type, element,
            problems));
        type.parameters().declared().forEach(parameter -> apply(parameter, context));
    }

    private static void apply(final ParameterInfo parameter, final Context context)
    {
        if (parameter.isStructure()) {
            structure(parameter, context);
            return;
        }
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
        final ParameterType type = PARAMETER_TYPES.get(parameter.dataType());
        validate(parameter, values, context);
        final String refusal = nodeTypeRefusal(parameter, type.storageType(), context);
        if (refusal != null) {
            report(refusal, parameter, context);
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
        validateCount(parameter, values.size(), context);
        for (final String value : values) {
            if (!parameter.options().isEmpty() && !parameter.options().contains(value)) {
                report("a parameter has a value it does not offer", parameter, context);
            }
        }
    }

    private static void validateCount(final ParameterInfo parameter, final int count, final Context context)
    {
        if (parameter.maxValues() > 0 && count > parameter.maxValues()) {
            report("a parameter has more values than it accepts", parameter, context);
        }
        if (count < parameter.minValues()) {
            report("a parameter has fewer values than it requires", parameter, context);
        }
    }

    /**
     * Reads a structure: one IAP-namespaced element per value, named after the parameter, whose fields are read
     * from it the way an extension element's parameters are read. A single structure is stored as a child node of
     * the parameter's name. A repeated one is a container of that name holding one node per element, in diagram
     * order. Each is named after its key field's value, escaped where a node name cannot hold it, and the key is
     * not stored again as a property.
     */
    private static void structure(final ParameterInfo parameter, final Context context)
    {
        if (parameter.location() != XmlLocation.EXTENSION_ELEMENT || context.extension() == null) {
            report("a structured parameter is not written inside an extension element", parameter, context);
            return;
        }
        final List<Element> elements = WorkflowDefinitionUtils.childElements(context.extension(),
            WorkflowDefinitionUtils.IAP_NS, parameter.name()).toList();
        if (elements.isEmpty()) {
            if (StringUtils.isNotBlank(context.extension().getAttribute(parameter.name()))) {
                report("a structured parameter is written as an attribute instead", parameter, context);
            }
            if (parameter.isRequired()) {
                report("a required parameter has no value", parameter, context);
            }
            return;
        }
        validateCount(parameter, elements.size(), context);
        final String refusal = structureRefusal(parameter, context);
        if (refusal != null) {
            report(refusal, parameter, context);
            return;
        }
        if (parameter.isMultiValued()) {
            repeated(parameter, elements, context);
            return;
        }
        final NodeBuilder stored = context.node().child(parameter.name());
        stored.setProperty(JCR_PRIMARY_TYPE, parameter.nodeType(), Type.NAME);
        fields(parameter, elements.get(0), stored, context.level().prefix() + parameter.name() + "/", context, null);
    }

    /** Stores the elements of a repeated structure in a container, one node per element named after its key. */
    private static void repeated(final ParameterInfo parameter, final List<Element> elements, final Context context)
    {
        final String key = parameter.keyParameter();
        if (parameter.fields().stream().noneMatch(field -> field.name().equals(key) && !field.isMultiValued()
            && !field.isStructure())) {
            report("a repeated structured parameter declares no single-valued key field", parameter, context);
            return;
        }
        final NodeBuilder container = context.node().child(parameter.name());
        container.setProperty(JCR_PRIMARY_TYPE, NT_UNSTRUCTURED, Type.NAME);
        final Set<String> keys = new LinkedHashSet<>();
        for (final Element element : elements) {
            final String value = element.getAttribute(key).trim();
            if (usableKey(value, keys, parameter, context)) {
                // A key is often a qualified name, such as the property a field sets
                final NodeBuilder stored = container.child(Text.escapeIllegalJcrChars(value));
                stored.setProperty(JCR_PRIMARY_TYPE, parameter.nodeType(), Type.NAME);
                fields(parameter, element, stored, context.level().prefix() + parameter.name() + "/" + value + "/",
                    context, key);
            }
        }
        // Oak keeps the order of an orderable node's children in this hidden property, which only the JCR API
        // maintains by itself
        container.setProperty(CHILD_ORDER, keys.stream().map(Text::escapeIllegalJcrChars).toList(), Type.NAMES);
    }

    /**
     * Whether one element's key can name its node, taking it if so and reporting why not if not. Escaping gives
     * distinct keys distinct names, so comparing the keys as written is enough.
     */
    private static boolean usableKey(final String key, final Set<String> taken, final ParameterInfo parameter,
        final Context context)
    {
        if (key.isEmpty()) {
            report("an element of a structured parameter has no key", parameter, context);
            return false;
        }
        if (!taken.add(key)) {
            report("an element of a structured parameter has the same key as another", parameter, context, key);
            return false;
        }
        return true;
    }

    /**
     * Reads the fields of one element of a structure into the node it is stored as, except the key, which names
     * the node instead. A field can only live inside the element, so one declaring another location is reported
     * and not read.
     */
    private static void fields(final ParameterInfo parameter, final Element holder, final NodeBuilder stored,
        final String prefix, final Context context, final String key)
    {
        final Context inner = context.into(new Level(holder, stored, parameter.nodeType(), prefix));
        for (final ParameterInfo field : parameter.fields()) {
            if (field.name().equals(key)) {
                continue;
            }
            if (field.location() != XmlLocation.EXTENSION_ELEMENT) {
                report("a field of a structured parameter is not written inside it", field, inner);
            } else {
                apply(field, inner);
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
     * Why the node type a parameter is stored in would not hold it as declared, or {@code null} when it would. The
     * vocabulary entry is what is wrong either way, so the value is not written.
     *
     * <p>One case is a property of the same name declared in another type or cardinality. Oak would not refuse the
     * value. A mismatched definition that is not mandatory gives way to the residual ones every flow node has. The
     * property would then have a shape its own node type rules out.</p>
     *
     * <p>The other is no definition at all, named or residual, as on a record of a strict node type. Oak would
     * refuse the property, and with it the whole save.</p>
     *
     * <p>Asked the way Oak asks it, of the definitions it flattens onto each registered type. A match is keyed by
     * the value's own type, or by {@code UNDEFINED}, in the same cardinality. A node type the registry does not
     * know is left to Oak, which refuses the node itself.</p>
     */
    private static String nodeTypeRefusal(final ParameterInfo parameter, final int storageType,
        final Context context)
    {
        final NodeState nodeType = context.nodeTypesRoot().getChildNode(context.level().nodeType());
        final boolean multiple = parameter.isMultiValued();
        final String typed = Type.fromTag(storageType, multiple).toString();
        final String untyped = Type.fromTag(PropertyType.UNDEFINED, multiple).toString();
        final NodeState named = nodeType.getChildNode(NAMED_PROPERTY_DEFINITIONS).getChildNode(parameter.name());
        if (named.exists()) {
            return named.hasChildNode(typed) || named.hasChildNode(untyped) ? null : DECLARED_DIFFERENTLY;
        }
        final NodeState residual = nodeType.getChildNode(RESIDUAL_PROPERTY_DEFINITIONS);
        return !nodeType.exists() || residual.hasChildNode(typed) || residual.hasChildNode(untyped) ? null
            : NOT_ALLOWED;
    }

    /**
     * Why the node a structure is stored as could not be written, or {@code null} when it could. Oak would refuse
     * each of these problems, and with it the whole save, so the structure is not written.
     *
     * <p>The structure's {@code jcrNodeType} may not be registered. It may be abstract or a mixin, which no node
     * can have as its primary type. It may have a mandatory child node, or a mandatory property besides its primary
     * type. That holds even for an autocreated property, since only the JCR API autocreates. It holds too for one a
     * field could supply, since a field may be left out or dropped.</p>
     *
     * <p>Or the node holding the structure may have no definition for a child of that name and type, named or
     * residual. A repeated structure is held in an {@code nt:unstructured} container, which takes records of any
     * type.</p>
     *
     * <p>Asked the way Oak asks it: a definition is keyed by the type it requires, which the child's own type or
     * any of its supertypes satisfies. An unregistered holder is treated as {@link #nodeTypeRefusal} treats one.</p>
     */
    private static String structureRefusal(final ParameterInfo parameter, final Context context)
    {
        final NodeState types = context.nodeTypesRoot();
        final String unwritable = recordTypeRefusal(types.getChildNode(parameter.nodeType()));
        if (unwritable != null) {
            return unwritable;
        }
        final NodeState holder = types.getChildNode(context.level().nodeType());
        final String stored = parameter.isMultiValued() ? NT_UNSTRUCTURED : parameter.nodeType();
        final Set<String> satisfied = new LinkedHashSet<>();
        satisfied.add(stored);
        types.getChildNode(stored).getNames(SUPERTYPES_PROPERTY).forEach(satisfied::add);
        final NodeState named = holder.getChildNode(NAMED_CHILD_NODE_DEFINITIONS).getChildNode(parameter.name());
        if (named.exists()) {
            return satisfied.stream().anyMatch(named::hasChildNode) ? null : DECLARED_DIFFERENTLY;
        }
        final NodeState residual = holder.getChildNode(RESIDUAL_CHILD_NODE_DEFINITIONS);
        return !holder.exists() || satisfied.stream().anyMatch(residual::hasChildNode) ? null : NOT_ALLOWED;
    }

    /** Why no node of a structure's own type could be written, or {@code null} when one could. */
    private static String recordTypeRefusal(final NodeState record)
    {
        if (!record.exists()) {
            return "a structured parameter is stored as a node type that is not registered";
        }
        if (record.getBoolean(IS_ABSTRACT) || record.getBoolean(IS_MIXIN)) {
            return "a structured parameter is stored as an abstract or mixin node type";
        }
        final boolean mandatory = record.getNames(MANDATORY_CHILD_NODES).iterator().hasNext()
            || StreamSupport.stream(record.getNames(MANDATORY_PROPERTIES).spliterator(), false)
                .anyMatch(name -> !JCR_PRIMARY_TYPE.equals(name));
        return mandatory ? "a structured parameter is stored as a node type with mandatory items" : null;
    }

    private static void report(final String problem, final ParameterInfo parameter, final Context context)
    {
        report(problem, parameter, context, null);
    }

    /**
     * Reports a problem with a parameter of the element being translated, naming it by its path from the flow
     * node. A problem about one record of a structure also names its key.
     */
    private static void report(final String problem, final ParameterInfo parameter, final Context context,
        final String key)
    {
        final String id = context.element().getAttribute(ID_ATTRIBUTE);
        final String name = context.level().prefix() + parameter.name();
        LOGGER.warn("Parameter {} of element {} in {}: {}", name, id, context.problems().path(), problem);
        context.problems().report(problem, context.problems().context()
            .with(WorkflowDefinitionUtils.ELEMENT_DETAIL, id).with(PARAMETER_DETAIL, name).with(KEY_DETAIL, key));
    }

    /** Reports a vocabulary declaration the parse cannot read, for each element of the entry's kind. */
    private static void reportDeclaration(final String problem, final String path, final FlowNodeTypeInfo type,
        final Element element, final ParseProblems problems)
    {
        problems.report(problem, problems.context()
            .with(WorkflowDefinitionUtils.ELEMENT_DETAIL, element.getAttribute(ID_ATTRIBUTE))
            .with("flowNodeType", type.name()).with(PARAMETER_DETAIL, path));
    }
}
