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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.jcr.PropertyType;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Decides whether the node holding a declared parameter would store it as declared. The answer depends only on the
 * vocabulary and the node type registry, so it is asked once per declaration, when the vocabulary is read.
 *
 * <p>The checks ask what Oak asks, of the definitions Oak flattens onto each registered type. A node type the
 * registry does not know is left to Oak, which refuses the node itself.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class StorageRefusals
{
    private static final String NAMED_PROPERTY_DEFINITIONS_CHILD = "rep:namedPropertyDefinitions";

    private static final String RESIDUAL_PROPERTY_DEFINITIONS_CHILD = "rep:residualPropertyDefinitions";

    private static final String NAMED_CHILD_NODE_DEFINITIONS_CHILD = "rep:namedChildNodeDefinitions";

    private static final String RESIDUAL_CHILD_NODE_DEFINITIONS_CHILD = "rep:residualChildNodeDefinitions";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String IS_ABSTRACT_PROPERTY = "jcr:isAbstract";

    private static final String IS_MIXIN_PROPERTY = "jcr:isMixin";

    private static final String AUTO_CREATED_PROPERTY = "jcr:autoCreated";

    private static final String MANDATORY_PROPERTIES_PROPERTY = "rep:mandatoryProperties";

    private static final String MANDATORY_CHILD_NODES_PROPERTY = "rep:mandatoryChildNodes";

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String DECLARED_DIFFERENTLY_PROBLEM =
        "a parameter disagrees with the node type it is stored in";

    private static final String NOT_ALLOWED_PROBLEM = "a parameter is not allowed by the node type it is stored in";

    private final NodeState registry;

    /**
     * Answers against one node type registry.
     *
     * @param registry the {@code /jcr:system/jcr:nodeTypes} node state
     */
    StorageRefusals(final NodeState registry)
    {
        this.registry = registry;
    }

    /**
     * Why a node of the holder's type would not hold a value parameter as declared, or {@code null} when it would.
     *
     * <p>One case is a property of the same name declared in another type or cardinality. Oak would not refuse the
     * value. A mismatched definition that is not mandatory gives way to a residual one, which every flow node type
     * has. The property would then have a shape its own node type rules out.</p>
     *
     * <p>The other is no definition at all, named or residual, as on a record of a strict node type. Oak would
     * refuse the property, and with it the whole save.</p>
     *
     * <p>A match is keyed by the value's own type, or by {@code UNDEFINED}, in the same cardinality.</p>
     *
     * @param holderType the node type of the node holding the value, blank when the entry declares none
     * @param name the parameter's name, which the property is named after
     * @param storageType the {@link PropertyType} the value is stored as
     * @param multiple whether the property holds a list
     * @return a problem phrase, or {@code null} when the value can be stored
     */
    String ofValue(final String holderType, final String name, final int storageType, final boolean multiple)
    {
        final NodeState holder = definition(holderType);
        final String typed = Type.fromTag(storageType, multiple).toString();
        final String untyped = Type.fromTag(PropertyType.UNDEFINED, multiple).toString();
        final NodeState named = holder.getChildNode(NAMED_PROPERTY_DEFINITIONS_CHILD).getChildNode(name);
        if (named.exists()) {
            return named.hasChildNode(typed) || named.hasChildNode(untyped) ? null : DECLARED_DIFFERENTLY_PROBLEM;
        }
        final NodeState residual = holder.getChildNode(RESIDUAL_PROPERTY_DEFINITIONS_CHILD);
        return !holder.exists() || residual.hasChildNode(typed) || residual.hasChildNode(untyped) ? null
            : NOT_ALLOWED_PROBLEM;
    }

    /**
     * Why the node a structure is stored as could not be written, or {@code null} when it could. Oak would refuse
     * such a node, and with it the whole save.
     *
     * <p>The structure's {@code jcrNodeType} may not be registered. It may be abstract or a mixin, which no node
     * can have as its primary type. It may have a mandatory child node, or a mandatory property besides its primary
     * type, that it does not autocreate. That holds even for one a field could supply, since a field may be left out
     * or dropped.</p>
     *
     * <p>Or the holder may have no definition for a child of that name and type, named or residual. A definition
     * is keyed by the type it requires, which the child's own type or any of its supertypes satisfies. A repeated
     * structure is held in an {@code nt:unstructured} container, which takes records of any type.</p>
     *
     * @param holderType the node type of the node holding the structure
     * @param name the parameter's name, which the structure's node is named after
     * @param recordType the node type each element is stored as
     * @param repeated whether the elements are held in a container rather than as the one child
     * @return a problem phrase, or {@code null} when the structure can be stored
     */
    String ofStructure(final String holderType, final String name, final String recordType, final boolean repeated)
    {
        final String unwritable = ofRecordType(definition(recordType));
        if (unwritable != null) {
            return unwritable;
        }
        final NodeState holder = definition(holderType);
        final String stored = repeated ? OrderedChildren.NT_UNSTRUCTURED_TYPE : recordType;
        final Set<String> satisfied = new LinkedHashSet<>();
        satisfied.add(stored);
        this.registry.getChildNode(stored).getNames(SUPERTYPES_PROPERTY).forEach(satisfied::add);
        final NodeState named = holder.getChildNode(NAMED_CHILD_NODE_DEFINITIONS_CHILD).getChildNode(name);
        if (named.exists()) {
            return satisfied.stream().anyMatch(named::hasChildNode) ? null : DECLARED_DIFFERENTLY_PROBLEM;
        }
        final NodeState residual = holder.getChildNode(RESIDUAL_CHILD_NODE_DEFINITIONS_CHILD);
        return !holder.exists() || satisfied.stream().anyMatch(residual::hasChildNode) ? null : NOT_ALLOWED_PROBLEM;
    }

    /** A node type's definition, which does not exist for a type that is blank or not registered. */
    private NodeState definition(final String type)
    {
        return StringUtils.isBlank(type) ? EmptyNodeState.MISSING_NODE : this.registry.getChildNode(type);
    }

    /** Why no node of a structure's own type could be written, or {@code null} when one could. */
    private static String ofRecordType(final NodeState record)
    {
        if (!record.exists()) {
            return "a structured parameter has a node type that is not registered";
        }
        if (record.getBoolean(IS_ABSTRACT_PROPERTY) || record.getBoolean(IS_MIXIN_PROPERTY)) {
            return "a structured parameter has an abstract or mixin node type";
        }
        final boolean mandatory = unfilled(record, MANDATORY_CHILD_NODES_PROPERTY, NAMED_CHILD_NODE_DEFINITIONS_CHILD)
            || unfilled(record, MANDATORY_PROPERTIES_PROPERTY, NAMED_PROPERTY_DEFINITIONS_CHILD);
        return mandatory ? "a structured parameter has a node type with mandatory items" : null;
    }

    /** Whether a node type has a mandatory item that neither the parse writes nor the type autocreates. */
    private static boolean unfilled(final NodeState type, final String mandatory, final String definitions)
    {
        return StreamSupport.stream(type.getNames(mandatory).spliterator(), false)
            .filter(name -> !JCR_PRIMARY_TYPE_PROPERTY.equals(name))
            .anyMatch(name -> StreamSupport.stream(
                type.getChildNode(definitions).getChildNode(name).getChildNodeEntries().spliterator(), false)
                .noneMatch(definition -> definition.getNodeState().getBoolean(AUTO_CREATED_PROPERTY)));
    }
}
