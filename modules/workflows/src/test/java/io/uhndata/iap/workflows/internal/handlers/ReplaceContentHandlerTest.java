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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReplaceContentHandler}: a child is replaced by the whole tree an event gives, checked against
 * the declarations of its types before anything is written, or removed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReplaceContentHandlerTest
{
    private static final String CARD = "/card";

    private static final String RULE = "/card/test:rule";

    private static final String CHECK = "test:Check";

    private static final String ALL_OF = "{'jcr:primaryType': 'test:AllOf', 'requireAll': true,"
        + " 'tagged': {'jcr:primaryType': 'test:Check', 'comparator': 'includes',"
        + "   'left': {'jcr:primaryType': 'test:Operand', 'source': 'tags'},"
        + "   'right': {'jcr:primaryType': 'test:Operand', 'value': ['a', 'b']}},"
        + " 'large': {'jcr:primaryType': 'test:Check', 'comparator': 'at least',"
        + "   'left': {'jcr:primaryType': 'test:Operand', 'source': 'answer', 'value': ['size']}}}";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ReplaceContentHandler handler = new ReplaceContentHandler();

    private final Activity activity = Mockito.mock(Activity.class);

    private FieldsFixture fixture;

    private Session session;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.fixture = new FieldsFixture(this.context);
        this.session = this.fixture.session();
        for (final String name : List.of("card", "made")) {
            this.session.getRootNode().addNode(name, "test:Box").addMixin("test:Ruled");
        }
        this.session.save();
        Mockito.when(this.activity.getPath()).thenReturn("/replace");
        Mockito.when(this.activity.get(ReplaceContentHandler.CHILD, String.class)).thenReturn("test:rule");
        Mockito.when(this.activity.get(ReplaceContentHandler.NODE_TYPES, String[].class))
            .thenReturn(new String[] { "test:AllOf", CHECK, "test:Operand", "test:Rule", "test:Ghost" });
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(ReplaceContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void writesTheWholeTree() throws WorkflowException, PersistenceException, RepositoryException
    {
        replace(ALL_OF);

        final Node rule = this.session.getNode(RULE);
        assertEquals("test:AllOf", rule.getPrimaryNodeType().getName());
        assertTrue(rule.getProperty("requireAll").getBoolean());
        assertEquals(List.of("tagged", "large"), this.fixture.children(RULE));
        final Node tagged = rule.getNode("tagged");
        assertEquals("includes", tagged.getProperty("comparator").getString());
        assertEquals("test/Check", tagged.getProperty("sling:resourceType").getString());
        assertEquals("tags", tagged.getProperty("left/source").getString());
        assertEquals(2, tagged.getProperty("right/value").getValues().length);
        // What its type creates of itself, when the tree does not give it
        final Node large = rule.getNode("large");
        assertEquals("literal", large.getProperty("right/source").getString());
        assertFalse(large.hasProperty("right/value"));
        assertEquals("answer", large.getProperty("left/source").getString());
    }

    @Test
    void storesValuesAsTheyAreGiven() throws WorkflowException, PersistenceException, RepositoryException
    {
        replace("{'jcr:primaryType': 'test:Check', 'comparator': 'is', 'due': '2026-09-29T00:00:00.000Z',"
            + " 'left': {'jcr:primaryType': 'test:Operand', 'value': 10, 'ratio': 2.5, 'flag': false,"
            + "   'mixed': [1, 2.5], 'none': [], 'note': null}}");

        final Node left = this.session.getNode(RULE + "/left");
        assertEquals(PropertyType.DATE, this.session.getNode(RULE).getProperty("due").getType());
        assertEquals(PropertyType.LONG, left.getProperty("value").getType());
        assertFalse(left.getProperty("value").isMultiple());
        assertEquals(PropertyType.DOUBLE, left.getProperty("ratio").getType());
        assertEquals(PropertyType.BOOLEAN, left.getProperty("flag").getType());
        assertEquals(PropertyType.DOUBLE, left.getProperty("mixed").getType());
        assertArrayEquals(new double[] { 1, 2.5 }, new double[] { left.getProperty("mixed").getValues()[0].getDouble(),
            left.getProperty("mixed").getValues()[1].getDouble() });
        assertTrue(left.getProperty("none").isMultiple());
        assertEquals(0, left.getProperty("none").getValues().length);
        assertFalse(left.hasProperty("note"));
    }

    @Test
    void replacesWhatWasThere() throws WorkflowException, PersistenceException, RepositoryException
    {
        replace(ALL_OF);
        replace("{'jcr:primaryType': 'test:Check', 'comparator': 'is empty',"
            + " 'left': {'jcr:primaryType': 'test:Operand', 'source': 'answer', 'value': ['size']},"
            + " 'right': {'jcr:primaryType': 'test:Operand', 'value': 'given'}}");

        final Node rule = this.session.getNode(RULE);
        assertEquals(CHECK, rule.getPrimaryNodeType().getName());
        assertEquals(List.of("left", "right"), this.fixture.children(RULE));
        assertEquals("given", rule.getProperty("right/value").getString());
    }

    @Test
    void removesItForNull() throws WorkflowException, PersistenceException, RepositoryException
    {
        replace(ALL_OF);
        replace("null");
        assertFalse(this.session.nodeExists(RULE));

        // Removing what is not there changes nothing
        replace(" null ");
        assertFalse(this.session.nodeExists(RULE));
    }

    @Test
    void refusesWhatCannotBeWritten() throws WorkflowException, PersistenceException, RepositoryException
    {
        replace(ALL_OF);
        final String check = "{'jcr:primaryType': 'test:Check', 'comparator': 'is',"
            + " 'left': {'jcr:primaryType': 'test:Operand'}";
        final String operand = check + ", 'right': {'jcr:primaryType': 'test:Operand', 'value': ";
        for (final String content : List.of("[]", "'x'", "3", "{", "{}", "{'jcr:primaryType': 5}",
            // Not listed, not a type, abstract, or not a type the child can have
            "{'jcr:primaryType': 'test:Item', 'title': 'x'}", "{'jcr:primaryType': 'test:Ghost'}",
            "{'jcr:primaryType': 'test:Rule'}", "{'jcr:primaryType': 'test:Operand'}",
            "{'jcr:primaryType': 'test:AllOf', 'x': {'jcr:primaryType': 'test:Operand'}}",
            // Mandatory, and not created of itself
            "{'jcr:primaryType': 'test:Check', 'left': {'jcr:primaryType': 'test:Operand'}}",
            "{'jcr:primaryType': 'test:Check', 'comparator': null, 'left': {'jcr:primaryType': 'test:Operand'}}",
            "{'jcr:primaryType': 'test:Check', 'comparator': {}, 'left': {'jcr:primaryType': 'test:Operand'}}",
            "{'jcr:primaryType': 'test:Check', 'comparator': 'is'}",
            "{'jcr:primaryType': 'test:Check', 'comparator': 'is', 'left': 'x'}",
            // Not names
            "{'jcr:primaryType': 'test:AllOf', 'x/y': " + check + "}}", check + ", 'a[1]': 1}", check + ", ':x': 1}",
            check + ", 'a:b:c': 1}", check + ", 'nope:x': 1}",
            // Protected, of the wrong kind, or not values
            check + ", 'sling:resourceType': 'x'}", check + ", 'jcr:mixinTypes': ['mix:referenceable']}",
            "{'jcr:primaryType': 'test:AllOf', 'requireAll': [true]}", check + ", 'due': 'soon'}",
            operand + "['a', 1]}}", operand + "[[1]]}}", operand + "[{}]}}", operand + "[null]}}",
            operand + "1000000000000000000000}}")) {
            assertThrows(InvalidPayloadException.class, () -> replace(content), content);
            assertFalse(this.session.hasPendingChanges(), content);
        }
        assertThrows(InvalidPayloadException.class, () -> replace(Map.of()));
        assertThrows(InvalidPayloadException.class, () -> replace(Map.of(ReplaceContentHandler.CONTENT_PARAMETER, 3)));
        assertEquals(List.of("tagged", "large"), this.fixture.children(RULE));
    }

    @Test
    void needsToKnowWhatItReplaces()
    {
        Mockito.when(this.activity.get(ReplaceContentHandler.NODE_TYPES, String[].class)).thenReturn(null);
        assertThrows(WorkflowDefinitionException.class, () -> replace(ALL_OF));

        Mockito.when(this.activity.get(ReplaceContentHandler.CHILD, String.class)).thenReturn(null);
        assertThrows(WorkflowDefinitionException.class, () -> replace(ALL_OF));
    }

    @Test
    void checksOutWhatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.session.getWorkspace().getVersionManager().checkin(CARD);

        replace(ALL_OF);

        assertTrue(this.session.nodeExists(RULE));
    }

    @Test
    void actsOnWhatWasCreated() throws WorkflowException, PersistenceException, RepositoryException
    {
        final WorkflowTaskContext task = this.fixture.task("condition", this.activity,
            Map.of(ReplaceContentHandler.CONTENT_PARAMETER, json(ALL_OF)), new HashMap<>());
        Mockito.when(task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE)).thenReturn("/made");
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource(CARD));

        this.handler.execute(task);

        assertTrue(this.session.nodeExists("/made/test:rule"));
        assertFalse(this.session.nodeExists(RULE));
    }

    @Test
    void reportsContentThatCannotBeRead() throws RepositoryException
    {
        final Resource target = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(target.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(target.getPath()).thenReturn(CARD);
        Mockito.when(node.isCheckedOut()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = this.fixture.task("condition", this.activity,
            Map.of(ReplaceContentHandler.CONTENT_PARAMETER, "null"), new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(target);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private void replace(final String content) throws WorkflowException, PersistenceException, RepositoryException
    {
        replace(Map.of(ReplaceContentHandler.CONTENT_PARAMETER, json(content)));
    }

    private void replace(final Map<String, Object> payload)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        final WorkflowTaskContext task = this.fixture.task("condition", this.activity, payload, new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource(CARD));
        this.handler.execute(task);
        this.session.save();
    }

    private static String json(final String content)
    {
        return content.replace('\'', '"');
    }
}
