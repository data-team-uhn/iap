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
package io.uhndata.iap.tags.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagPropagationEditor} and {@link TagPropagationEditorProvider}, driving full commits
 * through an {@link EditorHook} the same way the repository does.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TagPropagationEditorTest
{
    private static final String TYPE_PROPERTY = "sling:resourceType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String CONTENT_TYPE = "iap:TestContent";

    private static final String ENTITY_TYPE = "iap:TestEntity";

    private static final String COMPUTED = TagProcessor.Phase.LOCAL.getPropertyName();

    private static final String STATUS = "status";

    private static final String CONFIDENTIAL = "confidential";

    private static final String TAGS = TagManager.TAGS_PROPERTY;

    private static final String AGGREGATED = TagAggregationProcessor.PROPERTY;

    private static final String INHERITED = TagInheritanceProcessor.PROPERTY;

    private static final String INCOMPLETE = "incomplete";

    private static final String SENSITIVE = "sensitive";

    private static final String DATA = "data";

    private static final String ENTITY = "entity";

    private static final String PART = "part";

    private static final String ANSWER = "answer";

    private EditorHook hook;

    @BeforeEach
    void setUp() throws Exception
    {
        this.hook = hookWith(new TagInheritanceProcessor(), new TagAggregationProcessor());
    }

    /**
     * Builds a commit hook driving the given processors, as the repository would.
     *
     * @param processors the processors to register
     * @return a hook ready to process commits
     * @throws Exception if the provider's reference cannot be injected
     */
    private EditorHook hookWith(final TagProcessor... processors) throws Exception
    {
        final TagPropagationEditorProvider provider = new TagPropagationEditorProvider();
        final Field reference = TagPropagationEditorProvider.class.getDeclaredField("processors");
        reference.setAccessible(true);
        reference.set(provider, List.of(processors));
        return new EditorHook(provider);
    }

    @Test
    void aggregatedTagPropagatesToAllWritableAncestors() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty(TAGS, List.of(INCOMPLETE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA, ENTITY, PART));
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA, ENTITY));
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA));
        // The repository root carries no writable type, so it is never written to
        assertTrue(read(result, AGGREGATED).isEmpty());
        // The tagged node itself has no descendants carrying the tag
        assertTrue(read(result, AGGREGATED, DATA, ENTITY, PART, ANSWER).isEmpty());
        // Aggregated tags don't flow down
        assertTrue(read(result, INHERITED, DATA, ENTITY, PART, ANSWER).isEmpty());
    }

    @Test
    void removingTheSourceCleansUpAggregatedCopies() throws Exception
    {
        final NodeState before = tagged(TAGS, List.of(INCOMPLETE), DATA, ENTITY, PART, ANSWER);
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).removeProperty(TAGS);

        final NodeState result = process(before, after);

        assertTrue(read(result, AGGREGATED, DATA, ENTITY, PART).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA, ENTITY).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA).isEmpty());
    }

    @Test
    void inheritableTagSweepsDownIntoUnchangedDescendants() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY).setProperty(TAGS, List.of(SENSITIVE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, PART));
        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, PART, ANSWER));
        // The tagged node itself doesn't inherit its own tag, and nothing flows up or sideways
        assertTrue(read(result, INHERITED, DATA, ENTITY).isEmpty());
        assertTrue(read(result, INHERITED, DATA).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA).isEmpty());
    }

    @Test
    void removingTheSourceCleansUpInheritedCopies() throws Exception
    {
        final NodeState before = tagged(TAGS, List.of(SENSITIVE), DATA, ENTITY);
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY).removeProperty(TAGS);

        final NodeState result = process(before, after);

        assertTrue(read(result, INHERITED, DATA, ENTITY, PART).isEmpty());
        assertTrue(read(result, INHERITED, DATA, ENTITY, PART, ANSWER).isEmpty());
    }

    @Test
    void newNodeUnderTaggedAncestorInheritsOnCreation() throws Exception
    {
        final NodeState before = tagged(TAGS, List.of(SENSITIVE), DATA, ENTITY);
        final NodeBuilder after = before.builder();
        final NodeBuilder added = descend(after, DATA, ENTITY).child("attachment");
        added.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);

        final NodeState result = process(before, after);

        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, "attachment"));
    }

    @Test
    void newSubtreeWithAggregatedTagUpdatesExistingAncestors() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        final NodeBuilder entity2 = descend(after, DATA).child("entity2");
        entity2.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        final NodeBuilder part2 = entity2.child("part2");
        part2.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        part2.setProperty(TAGS, List.of(INCOMPLETE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA, "entity2"));
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA));
    }

    @Test
    void deletingTheContributingSubtreeCleansUpAggregatedCopies() throws Exception
    {
        final NodeState before = tagged(TAGS, List.of(INCOMPLETE), DATA, ENTITY, PART, ANSWER);
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).remove();

        final NodeState result = process(before, after);

        assertTrue(read(result, AGGREGATED, DATA, ENTITY, PART).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA, ENTITY).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA).isEmpty());
    }

    @Test
    void dualBehaviorTagFlowsBothWaysWithoutBouncing() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // Add a sibling of the tagged node to verify that aggregated copies don't flow back down
        final NodeBuilder sibling = descend(after, DATA, ENTITY).child("sibling");
        sibling.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        descend(after, DATA, ENTITY, PART).setProperty(TAGS, List.of("confidential"), Type.STRINGS);

        final NodeState result = process(before, after);

        assertEquals(Set.of("confidential"), read(result, AGGREGATED, DATA, ENTITY));
        assertEquals(Set.of("confidential"), read(result, INHERITED, DATA, ENTITY, PART, ANSWER));
        // The entity's aggregated copy is not re-inherited by the part's sibling
        assertTrue(read(result, INHERITED, DATA, ENTITY, "sibling").isEmpty());
    }

    @Test
    void nodesWithStrictTypesAreBoundaries() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // Strict nodes whose types reject extra properties, one with a writable node below it: a type with only
        // strict supertypes, a type with no supertypes at all, and an unregistered type
        final NodeBuilder file = descend(after, DATA, ENTITY).child("file");
        file.setProperty(PRIMARY_TYPE, "nt:file", Type.NAME);
        final NodeBuilder inner = file.child("inner");
        inner.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        descend(after, DATA, ENTITY).child("folder").setProperty(PRIMARY_TYPE, "nt:folder", Type.NAME);
        final NodeBuilder mystery = descend(after, DATA, ENTITY).child("mystery");
        mystery.setProperty(PRIMARY_TYPE, "custom:Unknown", Type.NAME);
        mystery.setProperty("jcr:mixinTypes", List.of("nt:file"), Type.NAMES);
        // A free-form node whose own type accepts any property is writable
        descend(after, DATA, ENTITY).child("free").setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);
        descend(after, DATA, ENTITY).setProperty(TAGS, List.of(SENSITIVE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, PART));
        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, "free"));
        // The boundary nodes are not written to, and the chain stops there
        assertTrue(read(result, INHERITED, DATA, ENTITY, "file").isEmpty());
        assertTrue(read(result, INHERITED, DATA, ENTITY, "file", "inner").isEmpty());
        assertTrue(read(result, INHERITED, DATA, ENTITY, "folder").isEmpty());
        assertTrue(read(result, INHERITED, DATA, ENTITY, "mystery").isEmpty());
    }

    @Test
    void mixinTypesMakeNodesWritable() throws Exception
    {
        final NodeState before = tagged(TAGS, List.of(SENSITIVE), DATA, ENTITY);
        final NodeBuilder after = before.builder();
        // A node whose primary type is strict, but which gains taggability through a mixin
        final NodeBuilder mixed = descend(after, DATA, ENTITY).child("mixed");
        mixed.setProperty(PRIMARY_TYPE, "nt:file", Type.NAME);
        mixed.setProperty("jcr:mixinTypes", List.of("nt:file", CONTENT_TYPE), Type.NAMES);

        final NodeState result = process(before, after);

        assertEquals(Set.of(SENSITIVE), read(result, INHERITED, DATA, ENTITY, "mixed"));
    }

    @Test
    void nonPropagatingAndUndefinedTagsStayPut() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART).setProperty(TAGS, List.of("draft", "unknown"), Type.STRINGS);

        final NodeState result = process(before, after);

        assertTrue(read(result, AGGREGATED, DATA, ENTITY).isEmpty());
        assertTrue(read(result, INHERITED, DATA, ENTITY, PART, ANSWER).isEmpty());
    }

    @Test
    void skippedSubtreesAreLeftAlone() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // Neither hidden nodes nor access control policies participate in tag propagation
        descend(after, DATA, ENTITY).child(":hidden").setProperty(TAGS, List.of(INCOMPLETE), Type.STRINGS);
        descend(after, DATA, ENTITY).child("rep:policy").setProperty(TAGS, List.of(INCOMPLETE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertTrue(read(result, AGGREGATED, DATA, ENTITY).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA).isEmpty());

        // Changes inside an already existing skipped subtree are ignored too, and the downward sweep of an
        // inheritable tag passes over skipped children
        final NodeBuilder again = result.builder();
        descend(again, DATA, ENTITY, "rep:policy").setProperty("modified", true);
        descend(again, DATA, ENTITY).setProperty(TAGS, List.of(SENSITIVE), Type.STRINGS);

        final NodeState swept = process(result, again);

        assertEquals(Set.of(SENSITIVE), read(swept, INHERITED, DATA, ENTITY, PART));
        assertTrue(read(swept, INHERITED, DATA, ENTITY, "rep:policy").isEmpty());
        assertTrue(read(swept, INHERITED, DATA, ENTITY, ":hidden").isEmpty());
    }

    @Test
    void missingDefinitionsMeanNoPropagation() throws Exception
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder entity = root.child(DATA);
        entity.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        final NodeState before = root.getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA).setProperty(TAGS, List.of(INCOMPLETE), Type.STRINGS);

        final NodeState result = process(before, after);

        assertTrue(read(result, AGGREGATED).isEmpty());
        assertTrue(read(result, INHERITED, DATA).isEmpty());
    }

    @Test
    void noProcessorsMeansNoEditor() throws Exception
    {
        final TagPropagationEditorProvider provider = new TagPropagationEditorProvider();
        assertEquals(null, provider.getRootEditor(EmptyNodeState.EMPTY_NODE, EmptyNodeState.EMPTY_NODE,
            EmptyNodeState.EMPTY_NODE.builder(), CommitInfo.EMPTY));
        final Field reference = TagPropagationEditorProvider.class.getDeclaredField("processors");
        reference.setAccessible(true);
        reference.set(provider, List.of());
        assertEquals(null, provider.getRootEditor(EmptyNodeState.EMPTY_NODE, EmptyNodeState.EMPTY_NODE,
            EmptyNodeState.EMPTY_NODE.builder(), CommitInfo.EMPTY));
    }

    @Test
    void locallyComputedTagsGetTheirOwnPropertyAndPropagateLikeExplicitOnes() throws Exception
    {
        final EditorHook local = hookWith(new StatusProcessor(), new TagAggregationProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, INCOMPLETE);

        final NodeState result = local.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        // The computed tag lands in the local phase's own property, not in the explicit tags
        assertEquals(Set.of(INCOMPLETE), read(result, COMPUTED, DATA, ENTITY, PART, ANSWER));
        assertTrue(read(result, TAGS, DATA, ENTITY, PART, ANSWER).isEmpty());
        // ...and from there it bubbles up exactly like a tag a user had placed by hand
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA, ENTITY, PART));
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA));
    }

    @Test
    void locallyComputedTagsAreRecomputedWhenTheirNodeChanges() throws Exception
    {
        final EditorHook local = hookWith(new StatusProcessor(), new TagAggregationProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder tagging = before.builder();
        descend(tagging, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, INCOMPLETE);
        final NodeState tagged = local.processCommit(before, tagging.getNodeState(), CommitInfo.EMPTY);

        final NodeBuilder after = tagged.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).removeProperty(STATUS);
        final NodeState result = local.processCommit(tagged, after.getNodeState(), CommitInfo.EMPTY);

        // The content the tag was computed from is gone, so the tag and every copy of it are gone too
        assertTrue(read(result, COMPUTED, DATA, ENTITY, PART, ANSWER).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA, ENTITY, PART).isEmpty());
        assertTrue(read(result, AGGREGATED, DATA).isEmpty());
    }

    @Test
    void localProcessorsSeeWhatTheirNodeInherited() throws Exception
    {
        final EditorHook local = hookWith(new TagInheritanceProcessor(), new EchoInheritedProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY).setProperty(TAGS, List.of(SENSITIVE), Type.STRINGS);

        final NodeState result = local.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        // The top-down phase runs before the local one on every node, including the swept descendants
        assertEquals(Set.of("draft"), read(result, COMPUTED, DATA, ENTITY, PART));
        assertEquals(Set.of("draft"), read(result, COMPUTED, DATA, ENTITY, PART, ANSWER));
        // The node the tag was placed on inherited nothing, so it computed nothing
        assertTrue(read(result, COMPUTED, DATA, ENTITY).isEmpty());
    }

    @Test
    void aFailingProcessorKeepsTheLastGoodTagsAndFlagsTheNode() throws Exception
    {
        final EditorHook working = hookWith(new StatusProcessor(), new TagAggregationProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder tagging = before.builder();
        descend(tagging, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, INCOMPLETE);
        final NodeState tagged = working.processCommit(before, tagging.getNodeState(), CommitInfo.EMPTY);

        final EditorHook broken = hookWith(new FailingProcessor(), new TagAggregationProcessor());
        final NodeBuilder after = tagged.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, "submitted");
        // The commit goes through: a user's data is never lost because a tag could not be computed
        final NodeState result = broken.processCommit(tagged, after.getNodeState(), CommitInfo.EMPTY);

        // A union missing one of its contributors would be worse than a stale one, so nothing was written
        assertEquals(Set.of(INCOMPLETE), read(result, COMPUTED, DATA, ENTITY, PART, ANSWER));
        assertTrue(descend(result.builder(), DATA, ENTITY, PART, ANSWER)
            .getBoolean(TagManager.COMPUTATION_FAILED_PROPERTY));
        // Only the phase that failed was skipped; the phases that worked still did their job
        assertEquals(Set.of(INCOMPLETE), read(result, AGGREGATED, DATA, ENTITY, PART));
    }

    @Test
    void aRecoveredNodeLosesItsFailureFlag() throws Exception
    {
        final EditorHook broken = hookWith(new FailingProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder failing = before.builder();
        descend(failing, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, INCOMPLETE);
        final NodeState flagged = broken.processCommit(before, failing.getNodeState(), CommitInfo.EMPTY);
        assertTrue(descend(flagged.builder(), DATA, ENTITY, PART, ANSWER)
            .getBoolean(TagManager.COMPUTATION_FAILED_PROPERTY));

        final EditorHook working = hookWith(new StatusProcessor());
        final NodeBuilder after = flagged.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, "submitted");
        final NodeState result = working.processCommit(flagged, after.getNodeState(), CommitInfo.EMPTY);

        assertEquals(Set.of("submitted"), read(result, COMPUTED, DATA, ENTITY, PART, ANSWER));
        assertFalse(descend(result.builder(), DATA, ENTITY, PART, ANSWER)
            .getBoolean(TagManager.COMPUTATION_FAILED_PROPERTY));
    }

    @Test
    void entityScopedProcessorsRecomputeEveryNodeOfAChangedEntity() throws Exception
    {
        final EditorHook scoped = hookWith(new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // Only the deepest node of the entity changes...
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty("secret", true);

        final NodeState result = scoped.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        // ...yet every node of the entity was recomputed, including the ones the commit never touched
        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, ENTITY));
        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, ENTITY, PART));
        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, ENTITY, PART, ANSWER));
        // Nothing outside the entity is in scope, so nothing there was computed
        assertTrue(read(result, COMPUTED, DATA).isEmpty());
    }

    @Test
    void entityScopedProcessorsRecomputeWhenPartOfTheEntityIsDeleted() throws Exception
    {
        final EditorHook scoped = hookWith(new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder secret = before.builder();
        descend(secret, DATA, ENTITY, PART, ANSWER).setProperty("secret", true);
        final NodeState tagged = scoped.processCommit(before, secret.getNodeState(), CommitInfo.EMPTY);
        assertEquals(Set.of(CONFIDENTIAL), read(tagged, COMPUTED, DATA, ENTITY));

        final NodeBuilder after = tagged.builder();
        descend(after, DATA, ENTITY, PART).remove();
        final NodeState result = scoped.processCommit(tagged, after.getNodeState(), CommitInfo.EMPTY);

        // Deleting the only node holding a secret is a change to the entity, so the whole entity is recomputed
        assertTrue(read(result, COMPUTED, DATA, ENTITY).isEmpty());
    }

    @Test
    void entityRecomputationLeavesSkippedSubtreesAlone() throws Exception
    {
        final EditorHook scoped = hookWith(new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // An access control policy inside the entity must never be written to, even by a whole-entity recomputation
        descend(after, DATA, ENTITY).child("rep:policy").setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty("secret", true);

        final NodeState result = scoped.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, ENTITY, PART));
        assertTrue(read(result, COMPUTED, DATA, ENTITY, "rep:policy").isEmpty());
    }

    @Test
    void entitiesOfTheBaseTypeItselfBoundTheScope() throws Exception
    {
        final EditorHook scoped = hookWith(new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // A node typed exactly iap:Entity, rather than a subtype of it, is just as much of a scope root
        final NodeBuilder plain = after.child(DATA).child("plain");
        plain.setProperty(PRIMARY_TYPE, "iap:Entity", Type.NAME);
        plain.child(PART).setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
        plain.setProperty("secret", true);

        final NodeState result = scoped.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, "plain", PART));
    }

    @Test
    void aSweepEnteringAnEntityPicksUpItsScope() throws Exception
    {
        final EditorHook scoped = hookWith(new TagInheritanceProcessor(), new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder seeded = before.builder();
        descend(seeded, DATA, ENTITY, PART).setProperty("secret", true);
        final NodeState tagged = scoped.processCommit(before, seeded.getNodeState(), CommitInfo.EMPTY);

        final NodeBuilder after = tagged.builder();
        // An inheritable tag placed outside the entity sweeps down through it, and the nodes the sweep reaches
        // inside the entity must be computed with that entity as their scope
        descend(after, DATA).setProperty(TAGS, List.of(SENSITIVE), Type.STRINGS);
        final NodeState result = scoped.processCommit(tagged, after.getNodeState(), CommitInfo.EMPTY);

        assertEquals(Set.of(CONFIDENTIAL), read(result, COMPUTED, DATA, ENTITY, PART));
        // The entity is tagged too, and "confidential" is inheritable, so the part inherits it alongside the
        // tag that started the sweep
        assertEquals(Set.of(SENSITIVE, CONFIDENTIAL), read(result, INHERITED, DATA, ENTITY, PART));
        // The node the sweep started from is outside any entity, so it has no scope to be computed against
        assertTrue(read(result, COMPUTED, DATA).isEmpty());
    }

    @Test
    void entityScopedProcessorsSeeNothingOutsideAnEntity() throws Exception
    {
        final EditorHook scoped = hookWith(new SecretProcessor());
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        // A secret placed outside any entity has no scope root to be found through
        descend(after, DATA).setProperty("secret", true);

        final NodeState result = scoped.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        assertTrue(read(result, COMPUTED, DATA).isEmpty());
    }

    @Test
    void processorsAreToldWhereTheyAre() throws Exception
    {
        final StatusProcessor status = new StatusProcessor();
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, DATA, ENTITY, PART, ANSWER).setProperty(STATUS, INCOMPLETE);

        hookWith(status).processCommit(before, after.getNodeState(), CommitInfo.EMPTY);

        assertTrue(status.paths.contains("/data/entity/part/answer"));
        assertTrue(status.paths.contains("/data"));
    }

    /**
     * Builds the shared test content: tag definitions for the aggregated {@code incomplete}, inheritable
     * {@code sensitive}, dual-behavior {@code confidential} and plain {@code draft} tags, plus an untagged
     * {@code /data/entity/part/answer} content chain.
     *
     * @return the root node builder
     */
    private NodeBuilder base()
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        // A minimal node type registry backing the writability checks: a content type declaring the tag
        // properties by name, a subtype of it, a free-form type accepting any property, and strict types
        final NodeBuilder types = root.child("jcr:system").child("jcr:nodeTypes");
        types.child("iap:Content").child("rep:namedPropertyDefinitions").child(TAGS);
        types.child(CONTENT_TYPE).setProperty("rep:supertypes", List.of("iap:Content"), Type.NAMES);
        types.child("iap:Entity");
        types.child(ENTITY_TYPE).setProperty("rep:supertypes", List.of("iap:Content", "iap:Entity"), Type.NAMES);
        types.child("nt:unstructured").child("rep:residualPropertyDefinitions");
        types.child("nt:file");
        types.child("nt:folder").setProperty("rep:supertypes", List.of("nt:base"), Type.NAMES);
        types.child("nt:base");
        final NodeBuilder homepage = root.child("Tags");
        homepage.setProperty(TYPE_PROPERTY, "iap/TagsHomepage");
        define(homepage, INCOMPLETE, true, false);
        define(homepage, SENSITIVE, false, true);
        define(homepage, "confidential", true, true);
        define(homepage, "draft", false, false);
        NodeBuilder node = root;
        for (final String name : List.of(DATA, ENTITY, PART, ANSWER)) {
            node = node.child(name);
            node.setProperty(PRIMARY_TYPE, ENTITY.equals(name) ? ENTITY_TYPE : CONTENT_TYPE, Type.NAME);
        }
        return root;
    }

    private void define(final NodeBuilder homepage, final String name, final boolean aggregated,
        final boolean inheritable)
    {
        final NodeBuilder definition = homepage.child(name);
        definition.setProperty(TYPE_PROPERTY, "iap/TagDefinition");
        definition.setProperty("aggregated", aggregated);
        definition.setProperty("inheritable", inheritable);
    }

    /**
     * Builds the shared test content with one extra property already set and processed through a commit, e.g. to
     * prepare the "before" state of a removal scenario.
     *
     * @param property the property to set
     * @param values the values of the property
     * @param path the path of the node to set the property on
     * @return the processed state, with all the derived tag properties in place
     * @throws Exception in case of commit failures
     */
    private NodeState tagged(final String property, final List<String> values, final String... path)
        throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, path).setProperty(property, values, Type.STRINGS);
        return process(before, after);
    }

    private NodeState process(final NodeState before, final NodeBuilder after) throws Exception
    {
        return this.hook.processCommit(before, after.getNodeState(), CommitInfo.EMPTY);
    }

    private NodeBuilder descend(final NodeBuilder root, final String... path)
    {
        NodeBuilder current = root;
        for (final String name : path) {
            current = current.getChildNode(name);
        }
        return current;
    }

    private Set<String> read(final NodeState root, final String property, final String... path)
    {
        NodeState current = root;
        for (final String name : path) {
            current = current.getChildNode(name);
        }
        return TagProcessor.readTags(current, property);
    }

    /**
     * Tags a node with the value of its own {@code status} property, the simplest possible computation from a node's
     * own content, and records every path it was asked about.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class StatusProcessor implements TagProcessor
    {
        private final List<String> paths = new ArrayList<>();

        @Override
        public Phase getPhase()
        {
            return Phase.LOCAL;
        }

        @Override
        public int getPriority()
        {
            return 100;
        }

        @Override
        public Set<String> computeTags(final TagContext context)
        {
            this.paths.add(context.getPath());
            final PropertyState status = context.getNode().getProperty(STATUS);
            return status == null ? Set.of() : Set.of(status.getValue(Type.STRING));
        }
    }

    /**
     * Computes a tag for every node that inherited anything, which only works if the top-down phase already ran.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class EchoInheritedProcessor implements TagProcessor
    {
        @Override
        public Phase getPhase()
        {
            return Phase.LOCAL;
        }

        @Override
        public int getPriority()
        {
            return 200;
        }

        @Override
        public Set<String> computeTags(final TagContext context)
        {
            return TagProcessor.readTags(context.getNode(), Phase.TOP_DOWN.getPropertyName()).isEmpty()
                ? Set.of() : Set.of("draft");
        }
    }

    /**
     * Throws on every node, standing in for a processor with a bug in it.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class FailingProcessor implements TagProcessor
    {
        @Override
        public Phase getPhase()
        {
            return Phase.LOCAL;
        }

        @Override
        public int getPriority()
        {
            return 100;
        }

        @Override
        public Set<String> computeTags(final TagContext context)
        {
            throw new IllegalStateException("This processor is broken");
        }
    }

    /**
     * Tags every node of an entity that holds a secret anywhere inside it, a computation no single node can answer
     * on its own.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class SecretProcessor implements TagProcessor
    {
        @Override
        public Phase getPhase()
        {
            return Phase.LOCAL;
        }

        @Override
        public Scope getScope()
        {
            return Scope.ENTITY;
        }

        @Override
        public int getPriority()
        {
            return 100;
        }

        @Override
        public Set<String> computeTags(final TagContext context)
        {
            final NodeState scope = context.getScopeRoot();
            return scope != null && holdsSecret(scope) ? Set.of(CONFIDENTIAL) : Set.of();
        }

        private boolean holdsSecret(final NodeState node)
        {
            if (node.hasProperty("secret")) {
                return true;
            }
            for (final ChildNodeEntry child : node.getChildNodeEntries()) {
                if (holdsSecret(child.getNodeState())) {
                    return true;
                }
            }
            return false;
        }
    }
}
