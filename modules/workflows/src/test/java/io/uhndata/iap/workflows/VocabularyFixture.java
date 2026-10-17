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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;

/**
 * The {@code /WorkflowTypes} vocabulary a BPMN parse reads, built entry by entry as a plain builder tree. Shared so
 * that every test about the parser declares entries, parameters and options the way the node types autocreate them.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class VocabularyFixture
{
    /** The vocabulary root, where every entry a test declares lives. */
    public static final String WORKFLOW_TYPES = "WorkflowTypes";

    /** The node type a service task is stored as. */
    public static final String ACTIVITY = "wf:Activity";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String LABEL = "label";

    private VocabularyFixture()
    {
        // Utility class, no instances allowed
    }

    /**
     * A repository holding the node type registry and an empty vocabulary.
     *
     * @return the root builder
     */
    public static NodeBuilder repository()
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        NodeTypesFixture.register(root);
        root.child(WORKFLOW_TYPES);
        return root;
    }

    /**
     * One vocabulary entry, identified by {@link #id(String) its name}.
     *
     * @param root the repository root builder
     * @param name the entry's node name, and its label
     * @param xmlElement the BPMN element it matches
     * @param jcrNodeType the node type an element it matches is stored as
     * @param priority how strongly it is preferred over other entries matching the same element
     * @return the entry builder
     */
    public static NodeBuilder entry(final NodeBuilder root, final String name, final String xmlElement,
        final String jcrNodeType, final long priority)
    {
        return root.child(WORKFLOW_TYPES).child(name)
            .setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME)
            .setProperty("jcr:uuid", id(name))
            .setProperty(LABEL, name)
            .setProperty("priority", priority)
            .setProperty("xmlElement", xmlElement)
            .setProperty("jcrNodeType", jcrNodeType);
    }

    /**
     * A per-handler entry: a service task recognized by the extension element it is written with.
     *
     * @param root the repository root builder
     * @param name the entry's node name
     * @param extensionElement the extension element that chooses it, as the entry names it
     * @return the entry builder
     */
    public static NodeBuilder handler(final NodeBuilder root, final String name, final String extensionElement)
    {
        return entry(root, name, "bpmn:serviceTask", ACTIVITY, 10)
            .setProperty("xmlExtensionElement", extensionElement);
    }

    /**
     * Declares one of the values a kind of node takes, written inside its entry's extension element.
     *
     * @param parent the entry or fields node declaring it
     * @param name the parameter's name, which is both the property it lands in and the attribute or element it
     *            is read from
     * @param dataType what may be put in it
     * @param minValues how many values are required, one or more making the parameter required
     * @param maxValues how many are accepted, {@code 0} for any number and anything but {@code 1} making the
     *            stored property a list
     * @return the parameter builder
     */
    public static NodeBuilder parameter(final NodeBuilder parent, final String name, final String dataType,
        final long minValues, final long maxValues)
    {
        return parent.child(name)
            .setProperty(PRIMARY_TYPE, "wf:Parameter", Type.NAME)
            .setProperty(LABEL, name)
            .setProperty("dataType", dataType)
            .setProperty("minValues", minValues)
            .setProperty("maxValues", maxValues);
    }

    /**
     * The same, for an optional value written as an IAP-namespaced attribute of the flow node's own element.
     *
     * @param parent the entry declaring it
     * @param name the parameter's name
     * @param dataType what may be put in it
     * @param maxValues how many are accepted
     * @return the parameter builder
     */
    public static NodeBuilder attributeParameter(final NodeBuilder parent, final String name, final String dataType,
        final long maxValues)
    {
        return parameter(parent, name, dataType, 0, maxValues).setProperty("xmlLocation", "attribute");
    }

    /**
     * The identity an entry is referred to by, stable per name so that a test can name the entry it expects a node
     * to point at.
     *
     * @param name the entry's node name, or any other string naming the node, such as its path
     * @return its {@code jcr:uuid}
     */
    public static String id(final String name)
    {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
