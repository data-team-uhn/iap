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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.tags.models.Taggable;

/**
 * Shared setup for the workflow model tests: the models under test, and the {@code /libs/wf} resource type
 * registry they dispatch through.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowFixture
{
    /** The lifecycle tags the engine tests place, with the categories their shipped definitions give them. */
    public static final Map<String, List<String>> LIFECYCLE_TAGS = Map.of(
        "draft", List.of("lifecycle"),
        "approved", List.of("lifecycle", "review"),
        "rejected", List.of("lifecycle", "review"));

    /** The {@code sling:resourceType} property name, spelled out often enough to be worth a constant. */
    public static final String TYPE = "sling:resourceType";

    /** The {@code sling:resourceSuperType} property name. */
    public static final String SUPER_TYPE = "sling:resourceSuperType";

    /** The property a {@code wf:WorkflowVersion} carries its lifecycle tag in, among any others. */
    public static final String TAGS = "tags";

    /**
     * The lifecycle tag of a version new instances start from. Read from the model, so renaming it fails these tests
     * to compile instead of quietly leaving them building versions the engine will skip.
     */
    public static final String ACTIVE = WorkflowVersion.ACTIVE_TAG;

    private WorkflowFixture()
    {
    }

    /**
     * Tags, the way a node stores them.
     *
     * @param names the tags' names
     * @return the value of a {@link #TAGS} property holding them
     */
    public static String[] tags(final String... names)
    {
        return names;
    }

    /**
     * Registers the workflow models along with the base models they extend, and recreates the {@code /libs/wf}
     * resource type registry shipped by this module.
     *
     * <p>The registry is the part that is easy to overlook: a resource only carries the one supertype its node type
     * autocreates, so following {@code wf/StartEvent} all the way up to {@code wf/FlowNode} — which is what
     * {@code getChildren(FlowNode.RESOURCE_TYPE, ...)} needs — only works because each type has a node under
     * {@code /libs} naming its own parent.</p>
     *
     * @param context the Sling context to set up
     */
    public static void setUp(final SlingContext context)
    {
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, EntityHomepage.class,
            SingleCondition.class, ConditionGroup.class, ConditionOperand.class);
        context.addModelsForPackage("io.uhndata.iap.workflows.models");

        registerType(context, "WorkflowsHomepage", "data/EntityHomepage");
        registerType(context, "WorkflowTypesHomepage", "data/EntityHomepage");
        registerType(context, "SystemWorkflowsHomepage", "data/EntityHomepage");
        registerType(context, "FlowNodeType", "data/Entity");
        registerType(context, "CatchingEventType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "ThrowingEventType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "ActivityType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "GatewayType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "Parameter", "data/EntityPart");
        registerType(context, "ParameterOption", "data/EntityPart");
        registerType(context, "WorkflowDefinition", "data/Entity");
        registerType(context, "WorkflowVersion", "data/Entity");
        registerType(context, "FlowNode", "data/EntityPart");
        registerType(context, "Event", FlowNode.RESOURCE_TYPE);
        registerType(context, "StartEvent", Event.RESOURCE_TYPE);
        registerType(context, "EndEvent", Event.RESOURCE_TYPE);
        registerType(context, "IntermediateEvent", Event.RESOURCE_TYPE);
        registerType(context, "IntermediateCatchingEvent", IntermediateEvent.RESOURCE_TYPE);
        registerType(context, "IntermediateThrowingEvent", IntermediateEvent.RESOURCE_TYPE);
        registerType(context, "Activity", FlowNode.RESOURCE_TYPE);
        registerType(context, "Gateway", FlowNode.RESOURCE_TYPE);
        registerType(context, "ExclusiveGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "ParallelGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "InclusiveGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "EventBasedGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "SequenceFlow", "data/EntityPart");
        registerType(context, "WorkflowInstances", "data/Content");
        registerType(context, "WorkflowInstance", "data/EntityPart");
        registerType(context, "WorkflowToken", "data/EntityPart");
        registerType(context, "TaskInstance", "data/EntityPart");
        registerType(context, "Variable", "data/EntityPart");
    }

    /**
     * Makes a JCR reference to the given path resolvable, the way a real repository would. Additive: a second call
     * teaches the session already in place about another identifier rather than replacing it, so a test needing two
     * resolvable references does not silently lose the first.
     *
     * @param context the Sling context to set up
     * @param identifier the identifier a reference property holds
     * @param path the path the identifier stands for
     * @throws RepositoryException never, only declared by the mocked JCR API
     */
    public static void resolveReference(final SlingContext context, final String identifier, final String path)
        throws RepositoryException
    {
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(path);
        Session session = context.resourceResolver().adaptTo(Session.class);
        if (session == null) {
            session = Mockito.mock(Session.class);
            context.registerAdapter(ResourceResolver.class, Session.class, session);
        }
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(target);
    }

    /**
     * Lets a resource say which node types it is of, as one stored in a repository does: its
     * {@code jcr:primaryType} and whatever that extends. Opt-in, since it changes what every resource adapts to
     * {@link Node} as.
     *
     * @param context the Sling context to set up
     * @param supertypes every type each node type extends, directly or not, for the node types that extend any
     */
    public static void answerNodeTypes(final SlingContext context, final Map<String, List<String>> supertypes)
    {
        context.registerAdapter(Resource.class, Node.class, (Function<Resource, Node>) resource -> {
            final String primaryType = resource.getValueMap().get("jcr:primaryType", String.class);
            final Node node = Mockito.mock(Node.class);
            try {
                Mockito.when(node.isNodeType(Mockito.anyString())).thenAnswer(invocation -> {
                    final String wanted = invocation.getArgument(0);
                    return primaryType != null && (wanted.equals(primaryType)
                        || supertypes.getOrDefault(primaryType, List.of()).contains(wanted));
                });
            } catch (final RepositoryException e) {
                // Cannot happen, for the reason stubTagging gives
                throw new IllegalStateException(e);
            }
            return node;
        });
    }

    private static void registerType(final SlingContext context, final String name, final String superType)
    {
        context.create().resource("/libs/wf/" + name, Map.of(SUPER_TYPE, superType));
    }

    /**
     * Registers the {@code Taggable} view, so a host adapts to it the way it does with the tags bundle
     * installed. The engine replaces a host's lifecycle tag when a workflow finishes, so without this every run
     * that reaches an end event carrying a {@code hostTag} fails on the absent tags service.
     *
     * <p>Records into the resource's own {@code tags} property, which is where the service writes for real, so
     * a test can read back what a workflow placed and what it retired.</p>
     *
     * @param context the mock context whose resources become taggable
     * @param categories each tag's categories, by tag name; a tag left out belongs to none
     */
    public static void enableTagging(final SlingContext context, final Map<String, List<String>> categories)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            try {
                stubTagging(taggable, resource, categories);
            } catch (final PersistenceException e) {
                // Stubbing never reaches the real method, so this cannot happen; the signature says otherwise
                throw new IllegalStateException(e);
            }
            return taggable;
        });
    }

    /**
     * The tags placed on a resource.
     *
     * @param resource a resource
     * @return its own tags, in order
     */
    public static Set<String> tags(final Resource resource)
    {
        return new LinkedHashSet<>(List.of(resource.getValueMap().get("tags", new String[0])));
    }

    /**
     * Replaces the tags placed on a resource.
     *
     * @param resource a resource
     * @param names its tags from now on
     */
    public static void writeTags(final Resource resource, final Set<String> names)
    {
        Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class)).put("tags", names.toArray(new String[0]));
    }

    /**
     * Teaches a mocked {@code Taggable} to replace and report tags on the resource's own property. Only replacing
     * with system tags allowed is stubbed, since that is how the engine moves a host between lifecycle tags.
     *
     * @param taggable the mock to stub
     * @param resource the resource it stands for
     * @param categories each tag's categories, by tag name
     * @throws PersistenceException never; the stubbed method declares it
     */
    private static void stubTagging(final Taggable taggable, final Resource resource,
        final Map<String, List<String>> categories) throws PersistenceException
    {
        Mockito.when(taggable.tagReplacing(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
            final Set<String> before = tags(resource);
            final Set<String> after = afterReplacing(before, invocation.getArgument(0), categories);
            writeTags(resource, after);
            return !after.equals(before);
        });
        Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
            tags(resource).contains(invocation.getArgument(0)));
    }

    /**
     * What a resource's tags become when a tag replaces those sharing any of its categories. A tag belonging to
     * no category retires nothing.
     *
     * @param tags the tags placed now
     * @param tag the tag being placed
     * @param categories each tag's categories, by tag name
     * @return the tags afterwards, in order
     */
    private static Set<String> afterReplacing(final Set<String> tags, final String tag,
        final Map<String, List<String>> categories)
    {
        final List<String> claimed = categories.getOrDefault(tag, List.of());
        final Set<String> result = new LinkedHashSet<>(tags);
        result.removeIf(existing -> !existing.equals(tag)
            && categories.getOrDefault(existing, List.of()).stream().anyMatch(claimed::contains));
        result.add(tag);
        return result;
    }
}
