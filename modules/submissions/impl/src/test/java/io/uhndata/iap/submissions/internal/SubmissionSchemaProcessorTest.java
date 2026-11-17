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
package io.uhndata.iap.submissions.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubmissionSchemaProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SubmissionSchemaProcessorTest
{
    private static final String SCHEMA_PATH = "/Schemas/timeOffRequest";

    private static final String SCHEMA_TITLE = "Time off request";

    /** Stands in for the schema as `dereference` inlined it, every version included. */
    private static final JsonValue INLINED_SCHEMA = Json.createObjectBuilder()
        .add("jcr:primaryType", "sch:Schema")
        .add("v1", Json.createObjectBuilder())
        .build();

    private final SubmissionSchemaProcessor processor = new SubmissionSchemaProcessor();

    private final Function<Node, JsonValue> serializeNode = node -> Json.createValue("the serialized node");

    @Test
    void isOnForEverySerializationAfterDereference()
    {
        assertEquals("schemaSummary", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    void printsTheSchemaOfASubmissionAsItsPathAndTitle() throws RepositoryException
    {
        final JsonValue result = this.processor.processProperty(node(true), schemaProperty(titledSchema()),
            INLINED_SCHEMA, this.serializeNode);

        assertEquals(Json.createObjectBuilder().add("@path", SCHEMA_PATH).add("title", SCHEMA_TITLE).build(), result);
    }

    @Test
    void leavesOutATitleTheReaderCannotSee() throws RepositoryException
    {
        final JsonValue result = this.processor.processProperty(node(true), schemaProperty(untitledSchema()),
            INLINED_SCHEMA, this.serializeNode);

        assertEquals(Json.createObjectBuilder().add("@path", SCHEMA_PATH).build(), result);
    }

    @Test
    void leavesTheSubmissionsOtherPropertiesAlone() throws RepositoryException
    {
        final Property version = Mockito.mock(Property.class);
        Mockito.when(version.getName()).thenReturn("schemaVersion");
        final JsonValue inlinedVersion = Json.createValue("the schema version, inlined");

        assertSame(inlinedVersion,
            this.processor.processProperty(node(true), version, inlinedVersion, this.serializeNode));
    }

    @Test
    void leavesASchemaPropertyOnAnotherTypeAlone() throws RepositoryException
    {
        assertSame(INLINED_SCHEMA, this.processor.processProperty(node(false),
            schemaProperty(titledSchema()), INLINED_SCHEMA, this.serializeNode));
    }

    @Test
    void leavesAnUnreachableSchemaAsItWasFound() throws RepositoryException
    {
        final Property schema = Mockito.mock(Property.class);
        Mockito.when(schema.getName()).thenReturn("schema");
        Mockito.when(schema.getNode()).thenThrow(new RepositoryException("not readable"));

        assertSame(INLINED_SCHEMA,
            this.processor.processProperty(node(true), schema, INLINED_SCHEMA, this.serializeNode));
    }

    private Node node(final boolean isSubmission) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType("sub:Submission")).thenReturn(isSubmission);
        return node;
    }

    private Node titledSchema() throws RepositoryException
    {
        final Node schema = untitledSchema();
        final Property title = Mockito.mock(Property.class);
        Mockito.when(title.getString()).thenReturn(SCHEMA_TITLE);
        Mockito.when(schema.hasProperty("title")).thenReturn(true);
        Mockito.when(schema.getProperty("title")).thenReturn(title);
        return schema;
    }

    private Node untitledSchema() throws RepositoryException
    {
        final Node schema = Mockito.mock(Node.class);
        Mockito.when(schema.getPath()).thenReturn(SCHEMA_PATH);
        return schema;
    }

    private Property schemaProperty(final Node target) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn("schema");
        Mockito.when(property.getNode()).thenReturn(target);
        return property;
    }
}
