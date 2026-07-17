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

package io.uhndata.iap.serialization.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link DereferenceProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DereferenceProcessorTest
{
    private static final JsonValue SERIALIZED = Json.createValue("the serialized node");

    private final DereferenceProcessor processor = new DereferenceProcessor();

    private final Function<Node, JsonValue> serializeNode = n -> SERIALIZED;

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("dereference", this.processor.getName());
        Assertions.assertEquals(10, this.processor.getPriority());
        Assertions.assertTrue(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testNonReferencePropertyIsLeftUnmodified()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.STRING, "text");
        final JsonValue input = Json.createValue("previous value");

        Assertions.assertSame(input, this.processor.processProperty(null, property, input, this.serializeNode));
    }

    @Test
    public void testSingleReferenceIsDereferenced()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.REFERENCE, "question");
        final Node target = Mockito.mock(Node.class);
        Mockito.when(property.getNode()).thenReturn(target);

        Assertions.assertSame(SERIALIZED, this.processor.processProperty(null, property, null, this.serializeNode));
    }

    @Test
    public void testSingleJcrReferenceIsSerializedAsPath()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.REFERENCE, "jcr:baseVersion");
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn("/jcr:system/jcr:versionStorage/42");
        Mockito.when(property.getNode()).thenReturn(target);

        final JsonValue result = this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals("/jcr:system/jcr:versionStorage/42", ((JsonString) result).getString());
    }

    @Test
    public void testSingleInaccessibleReferenceIsLeftUnmodified()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.REFERENCE, "question");
        Mockito.when(property.getNode()).thenThrow(new RepositoryException());
        final JsonValue input = Json.createValue("previous value");

        Assertions.assertSame(input, this.processor.processProperty(null, property, input, this.serializeNode));
    }

    @Test
    public void testMultiReferenceIsDereferenced()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.REFERENCE, "questions", "uuid-1", "uuid-2");
        final Node target = Mockito.mock(Node.class);
        Mockito.when(property.getSession().getNodeByIdentifier(Mockito.anyString())).thenReturn(target);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(SERIALIZED, result.get(0));
        Assertions.assertEquals(SERIALIZED, result.get(1));
    }

    @Test
    public void testMultiJcrReferenceIsSerializedAsPaths()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.REFERENCE, "jcr:predecessors", "uuid-1");
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn("/jcr:system/jcr:versionStorage/42");
        Mockito.when(property.getSession().getNodeByIdentifier("uuid-1")).thenReturn(target);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals("/jcr:system/jcr:versionStorage/42", result.getString(0));
    }

    @Test
    public void testMultiInaccessibleReferenceIsLeftUnmodified()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.REFERENCE, "questions", "uuid-1");
        Mockito.when(property.getSession().getNodeByIdentifier("uuid-1")).thenThrow(new RepositoryException());
        final JsonValue input = Json.createValue("previous value");

        Assertions.assertSame(input, this.processor.processProperty(null, property, input, this.serializeNode));
    }

    @Test
    public void testMultiAbsolutePathIsDereferenced()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.PATH, "related", "/some/target");
        final Node target = Mockito.mock(Node.class);
        Mockito.when(property.getSession().getNode("/some/target")).thenReturn(target);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals(SERIALIZED, result.get(0));
    }

    @Test
    public void testMultiRelativePathIsDereferenced()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.PATH, "related", "sibling");
        final Node parent = Mockito.mock(Node.class);
        final Node target = Mockito.mock(Node.class);
        Mockito.when(property.getParent()).thenReturn(parent);
        Mockito.when(parent.getNode("sibling")).thenReturn(target);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals(SERIALIZED, result.get(0));
    }

    @Test
    public void testMultiInaccessiblePathIsSerializedAsPath()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.PATH, "related", "/some/target");
        Mockito.when(property.getSession().getNode("/some/target")).thenThrow(new RepositoryException());

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, this.serializeNode);

        Assertions.assertEquals("/some/target", result.getString(0));
    }

    @Test
    public void testMultiNonReferencePropertyIsLeftUnmodified()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.STRING, "text", "value");
        final JsonValue input = Json.createValue("previous value");

        Assertions.assertSame(input, this.processor.processProperty(null, property, input, this.serializeNode));
    }

    @Test
    public void testInaccessiblePropertyIsLeftUnmodified()
        throws Exception
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.isMultiple()).thenThrow(new RepositoryException());
        final JsonValue input = Json.createValue("previous value");

        Assertions.assertSame(input, this.processor.processProperty(null, property, input, this.serializeNode));
    }

    private Property mockProperty(final int type, final String name)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.isMultiple()).thenReturn(false);
        Mockito.when(property.getType()).thenReturn(type);
        Mockito.when(property.getName()).thenReturn(name);
        return property;
    }

    private Property mockMultiProperty(final int type, final String name, final String... rawValues)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        final Session session = Mockito.mock(Session.class);
        final Value[] values = new Value[rawValues.length];
        for (int i = 0; i < rawValues.length; ++i) {
            values[i] = Mockito.mock(Value.class);
            Mockito.when(values[i].getString()).thenReturn(rawValues[i]);
        }
        Mockito.when(property.isMultiple()).thenReturn(true);
        Mockito.when(property.getType()).thenReturn(type);
        Mockito.when(property.getName()).thenReturn(name);
        Mockito.when(property.getSession()).thenReturn(session);
        Mockito.when(property.getValues()).thenReturn(values);
        return property;
    }
}
