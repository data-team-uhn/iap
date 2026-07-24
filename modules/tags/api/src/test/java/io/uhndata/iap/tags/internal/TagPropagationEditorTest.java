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
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final TagPropagationEditorProvider provider = new TagPropagationEditorProvider();
        final Field reference = TagPropagationEditorProvider.class.getDeclaredField("processors");
        reference.setAccessible(true);
        reference.set(provider, List.of(new TagInheritanceProcessor(), new TagAggregationProcessor()));
        this.hook = new EditorHook(provider);
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
            node.setProperty(PRIMARY_TYPE, CONTENT_TYPE, Type.NAME);
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
}
