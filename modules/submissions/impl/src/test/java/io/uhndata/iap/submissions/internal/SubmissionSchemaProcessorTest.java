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
import jakarta.json.JsonString;
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
        assertEquals("schemaPath", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    void printsTheSchemaOfASubmissionAsItsPath() throws RepositoryException
    {
        final JsonValue result = this.processor.processProperty(node(true), schemaProperty(SCHEMA_PATH),
            INLINED_SCHEMA, this.serializeNode);

        assertEquals(SCHEMA_PATH, ((JsonString) result).getString());
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
        assertSame(INLINED_SCHEMA, this.processor.processProperty(node(false), schemaProperty(SCHEMA_PATH),
            INLINED_SCHEMA, this.serializeNode));
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

    private Property schemaProperty(final String targetPath) throws RepositoryException
    {
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(targetPath);
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn("schema");
        Mockito.when(property.getNode()).thenReturn(target);
        return property;
    }
}
