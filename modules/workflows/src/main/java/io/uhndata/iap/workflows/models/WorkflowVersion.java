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
package io.uhndata.iap.workflows.models;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code wf:WorkflowVersion} node: one immutable revision of a
 * {@link WorkflowDefinition workflow}, holding both the BPMN source it was authored as and the graph of
 * {@link FlowNode flow nodes} that source was parsed into. Everything that runs, runs against a version.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = WorkflowVersion.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowVersion extends Entity
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowVersion} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowVersion";

    /** The name of the {@code nt:file} child holding the BPMN source. */
    private static final String BPMN_FILE = "bpmn.xml";

    @ValueMapValue
    private String version;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private boolean active;

    @ValueMapValue
    private String bpmnXmlParsedHash;

    @ValueMapValue
    private String targetResourceType;

    /**
     * The version label, e.g. "1.0".
     *
     * @return a version label
     */
    @NotNull
    public String getVersion()
    {
        return this.version;
    }

    /**
     * An optional description of what this version of the workflow does.
     *
     * @return a description, or {@code null} if not set
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether new instances may be created from this version.
     *
     * @return {@code true} if this version accepts new instances
     */
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * The resource type of the node whose events this version handles, e.g. {@code wf/WorkflowsHomepage} —
     * how the engine matches an incoming event's target to a system workflow. User workflows don't need it,
     * since they are reached through the schema version that references them.
     *
     * @return a resource type, or {@code null} when this version is not matched by target
     */
    @Nullable
    public String getTargetResourceType()
    {
        return this.targetResourceType;
    }

    /**
     * The file holding the BPMN 2.0 XML this version was authored as: the source of truth the
     * {@link #getFlowNodes flow nodes} were parsed from, and what the visual editor loads and saves. It is an
     * {@code nt:file} child rather than a property, so that a diagram can be downloaded and re-uploaded as the
     * document it is, and so that it does not weigh on every serialization of the version. The file is returned
     * rather than its contents: a caller that wants the XML adapts it to an {@code InputStream} and decides for
     * itself how to read a document of unknown size.
     *
     * @return the BPMN source file, or {@code null} if this version has no source yet
     */
    @Nullable
    public Resource getBpmnFile()
    {
        return this.resource.getChild(BPMN_FILE);
    }

    /**
     * The hash of the {@link #getBpmnFile BPMN source} as of the last successful parse into flow nodes. Comparing it
     * against the hash of the current source is how a stale graph is detected.
     *
     * @return a SHA-256 hash, or {@code null} if this version was never parsed
     */
    @Nullable
    public String getBpmnXmlParsedHash()
    {
        return this.bpmnXmlParsedHash;
    }

    /**
     * The flow nodes making up this version's graph, each adapted to the model of its own specific type. Only the
     * top-level nodes are listed; the boundary events attached to an {@link Activity} are reached through it.
     *
     * <p>A child carrying one of the abstract resource types is left out rather than guessed at — see
     * {@link ModelDispatch#isConcrete}.</p>
     *
     * @return a list of flow nodes, empty if this version has not been parsed
     */
    @NotNull
    public List<FlowNode> getFlowNodes()
    {
        return this.getChildren(FlowNode.RESOURCE_TYPE, FlowNode.class).stream()
            .filter(ModelDispatch::isConcrete)
            .toList();
    }

    /**
     * Every flow node of this version, boundary events included, flattened out of the tree the graph is stored as.
     * Where {@link #getFlowNodes()} gives the top-level nodes, this gives all of them, which is what any pass over
     * the whole graph — validation, rendering, finding what leads into a node — has to walk.
     *
     * @return a list of flow nodes, empty if this version has not been parsed
     */
    @NotNull
    public List<FlowNode> getAllFlowNodes()
    {
        return flatten(this.getFlowNodes());
    }

    /**
     * The events a new instance of this version may be started by. A workflow with none cannot be instantiated.
     *
     * @return a list of start events, empty if none
     */
    @NotNull
    public List<StartEvent> getStartEvents()
    {
        return this.getChildren(StartEvent.RESOURCE_TYPE, StartEvent.class);
    }

    /**
     * Looks up a flow node of this version by its BPMN element identifier, which is how the graph refers to itself:
     * a {@link SequenceFlow} names its target this way, and a {@link WorkflowToken} names where it rests. Boundary
     * events are found too, even though they hang off their activity rather than off the version.
     *
     * @param elementId the BPMN element identifier to look for, which the callers read from properties that are
     *            mandatory but may be missing on a malformed node, so it may be {@code null}
     * @return the matching flow node, or {@code null} if this version has no node with that identifier, or none
     *         was asked for
     */
    @Nullable
    public FlowNode getFlowNode(@Nullable final String elementId)
    {
        return findFlowNode(this.getFlowNodes(), elementId);
    }

    /**
     * Depth-first search for a flow node with the given element identifier, descending into the nodes nested inside
     * each candidate. Lazily evaluated, so the search stops at the first match instead of walking the whole graph.
     *
     * @param nodes the nodes to search through
     * @param elementId the BPMN element identifier to look for, or {@code null}, which matches nothing — an arc
     *            without a target names no node, and that is an absent node rather than an error
     * @return the matching flow node, or {@code null} if none of these nodes, nor anything nested in them, matches
     */
    @Nullable
    private static FlowNode findFlowNode(@NotNull final List<FlowNode> nodes, @Nullable final String elementId)
    {
        if (elementId == null) {
            return null;
        }
        return nodes.stream()
            .map(node -> elementId.equals(node.getElementId()) ? node : findFlowNode(node.getNestedNodes(), elementId))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Flattens a list of flow nodes together with everything nested inside them, parents before their children.
     *
     * @param nodes the nodes to flatten
     * @return every one of those nodes and their descendants
     */
    @NotNull
    private static List<FlowNode> flatten(@NotNull final List<FlowNode> nodes)
    {
        return nodes.stream()
            .flatMap(node -> Stream.concat(Stream.of(node), flatten(node.getNestedNodes()).stream()))
            .toList();
    }
}
