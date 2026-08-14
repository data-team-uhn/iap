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

package io.uhndata.iap.schemas.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link SimpleSchemaVersionProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SimpleSchemaVersionProcessorTest
{
    /** Stands in for whatever the workflow reference was inlined as by the time this processor runs. */
    private static final JsonValue INLINED_WORKFLOW = Json.createObjectBuilder()
        .add("jcr:primaryType", "wf:WorkflowVersion")
        .add("bpmn", "<the whole document>")
        .build();

    private final SimpleSchemaVersionProcessor processor = new SimpleSchemaVersionProcessor();

    private final Function<Node, JsonValue> serializeNode = n -> Json.createValue("the serialized node");

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("simple", this.processor.getName());
        // After the general trimming at 25, and after dereference at 10 has inlined what this undoes
        Assertions.assertEquals(50, this.processor.getPriority());
        Assertions.assertFalse(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testWorkflowIsLeftAsTheIdentifierItIsStoredAs()
        throws Exception
    {
        final Node version = mockSchemaVersion(true);
        final Property workflow = mockProperty("workflow", "uuid-wf-1");

        final JsonValue result =
            this.processor.processProperty(version, workflow, INLINED_WORKFLOW, this.serializeNode);

        Assertions.assertEquals("uuid-wf-1", ((JsonString) result).getString());
    }

    @Test
    public void testTheVersionsOwnPropertiesAreKept()
        throws Exception
    {
        final Node version = mockSchemaVersion(true);
        final Property property = mockProperty("version", "1.0");
        final JsonValue input = Json.createValue("1.0");

        Assertions.assertSame(input, this.processor.processProperty(version, property, input, this.serializeNode));
    }

    @Test
    public void testAWorkflowOnAnotherTypeIsLeftAlone()
        throws Exception
    {
        final Node other = mockSchemaVersion(false);
        final Property workflow = mockProperty("workflow", "uuid-wf-1");

        Assertions.assertSame(INLINED_WORKFLOW,
            this.processor.processProperty(other, workflow, INLINED_WORKFLOW, this.serializeNode));
    }

    @Test
    public void testAnUnreadablePropertyIsLeftAsItWasFound()
        throws Exception
    {
        final Node version = mockSchemaVersion(true);
        final Property workflow = Mockito.mock(Property.class);
        Mockito.when(workflow.getName()).thenThrow(new RepositoryException());

        Assertions.assertSame(INLINED_WORKFLOW,
            this.processor.processProperty(version, workflow, INLINED_WORKFLOW, this.serializeNode));
    }

    @Test
    public void testTheRequirementsAreLeftOut()
        throws Exception
    {
        final Node version = mockSchemaVersion(true);
        final JsonValue requirement = Json.createValue("a whole requirement subtree");

        Assertions.assertNull(this.processor.processChild(version, Mockito.mock(Node.class), requirement,
            this.serializeNode));
    }

    @Test
    public void testTheChildrenOfAnotherTypeAreKept()
        throws Exception
    {
        final Node other = mockSchemaVersion(false);
        final JsonValue child = Json.createValue("a version of a schema");

        Assertions.assertSame(child,
            this.processor.processChild(other, Mockito.mock(Node.class), child, this.serializeNode));
    }

    @Test
    public void testANodeOfUnreadableTypeIsNotSummarized()
        throws Exception
    {
        final Node broken = Mockito.mock(Node.class);
        Mockito.when(broken.isNodeType(Mockito.anyString())).thenThrow(new RepositoryException());
        final JsonValue child = Json.createValue("a child");

        Assertions.assertSame(child,
            this.processor.processChild(broken, Mockito.mock(Node.class), child, this.serializeNode));
    }

    @Test
    public void testTheTopLevelHasNoContainingNode()
    {
        final JsonValue child = Json.createValue("a child");

        // The serializer passes the containing node, but a defensive null must not be mistaken for a schema version
        Assertions.assertSame(child, this.processor.processChild(null, null, child, this.serializeNode));
    }

    private Node mockSchemaVersion(final boolean isVersion)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType("sch:SchemaVersion")).thenReturn(isVersion);
        return node;
    }

    private Property mockProperty(final String name, final String value)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn(name);
        Mockito.when(property.getString()).thenReturn(value);
        return property;
    }
}
