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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;

import javax.jcr.Binary;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PropertiesProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class PropertiesProcessorTest
{
    private final PropertiesProcessor processor = new PropertiesProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("properties", this.processor.getName());
        Assertions.assertEquals(0, this.processor.getPriority());
        Assertions.assertTrue(this.processor.isEnabledByDefault(null));
        Assertions.assertTrue(this.processor.canProcess(null));
    }

    @Test
    public void testExistingInputIsLeftUnmodified()
        throws Exception
    {
        final JsonValue input = Json.createValue("already computed");
        Assertions.assertSame(input, this.processor.processProperty(null, mockProperty(PropertyType.STRING), input,
            null));
    }

    @Test
    public void testStringProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.STRING);
        Mockito.when(property.getValue().getString()).thenReturn("some text");

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("some text", ((JsonString) result).getString());
    }

    @Test
    public void testBooleanProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.BOOLEAN);
        Mockito.when(property.getValue().getBoolean()).thenReturn(true);

        Assertions.assertSame(JsonValue.TRUE, this.processor.processProperty(null, property, null, null));

        Mockito.when(property.getValue().getBoolean()).thenReturn(false);

        Assertions.assertSame(JsonValue.FALSE, this.processor.processProperty(null, property, null, null));
    }

    @Test
    public void testLongProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.LONG);
        Mockito.when(property.getValue().getLong()).thenReturn(42L);

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals(42L, ((JsonNumber) result).longValue());
    }

    @Test
    public void testDoubleProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.DOUBLE);
        Mockito.when(property.getValue().getDouble()).thenReturn(3.25);

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals(3.25, ((JsonNumber) result).doubleValue(), 0);
    }

    @Test
    public void testDecimalPropertyIsSerializedAsString()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.DECIMAL);
        Mockito.when(property.getValue().getString()).thenReturn("123.456789012345678901");

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("123.456789012345678901", ((JsonString) result).getString());
    }

    @Test
    public void testDateProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.DATE);
        final Calendar date = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        date.setTimeInMillis(0);
        Mockito.when(property.getValue().getDate()).thenReturn(date);

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("1970-01-01T00:00:00.000Z", ((JsonString) result).getString());
    }

    @Test
    public void testBinaryProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.BINARY);
        final Binary binary = Mockito.mock(Binary.class);
        Mockito.when(binary.getStream())
            .thenReturn(new ByteArrayInputStream("binary content".getBytes(StandardCharsets.ISO_8859_1)));
        Mockito.when(property.getValue().getBinary()).thenReturn(binary);

        final JsonValue result = this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("binary content", ((JsonString) result).getString());
    }

    @Test
    public void testUnreadableBinaryProperty()
        throws Exception
    {
        final Property property = mockProperty(PropertyType.BINARY);
        final Binary binary = Mockito.mock(Binary.class);
        final InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(stream.read(Mockito.any(byte[].class))).thenThrow(new IOException());
        Mockito.when(stream.read(Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
            .thenThrow(new IOException());
        Mockito.when(binary.getStream()).thenReturn(stream);
        Mockito.when(property.getValue().getBinary()).thenReturn(binary);

        Assertions.assertSame(JsonValue.NULL, this.processor.processProperty(null, property, null, null));
    }

    @Test
    public void testInaccessibleProperty()
        throws Exception
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.isMultiple()).thenThrow(new RepositoryException());

        Assertions.assertSame(JsonValue.NULL, this.processor.processProperty(null, property, null, null));
    }

    @Test
    public void testMultiValuedStringProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.STRING, 2);
        Mockito.when(property.getValues()[0].getString()).thenReturn("first");
        Mockito.when(property.getValues()[1].getString()).thenReturn("second");

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("first", result.getString(0));
        Assertions.assertEquals("second", result.getString(1));
    }

    @Test
    public void testMultiValuedBooleanProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.BOOLEAN, 1);
        Mockito.when(property.getValues()[0].getBoolean()).thenReturn(true);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertTrue(result.getBoolean(0));
    }

    @Test
    public void testMultiValuedLongProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.LONG, 1);
        Mockito.when(property.getValues()[0].getLong()).thenReturn(42L);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals(42L, result.getJsonNumber(0).longValue());
    }

    @Test
    public void testMultiValuedDoubleProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.DOUBLE, 1);
        Mockito.when(property.getValues()[0].getDouble()).thenReturn(3.25);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals(3.25, result.getJsonNumber(0).doubleValue(), 0);
    }

    @Test
    public void testMultiValuedDateProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.DATE, 1);
        final Calendar date = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        date.setTimeInMillis(0);
        Mockito.when(property.getValues()[0].getDate()).thenReturn(date);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("1970-01-01T00:00:00.000Z", result.getString(0));
    }

    @Test
    public void testMultiValuedBinaryProperty()
        throws Exception
    {
        final Property property = mockMultiProperty(PropertyType.BINARY, 1);
        final Binary binary = Mockito.mock(Binary.class);
        Mockito.when(binary.getStream())
            .thenReturn(new ByteArrayInputStream("binary content".getBytes(StandardCharsets.ISO_8859_1)));
        Mockito.when(property.getValues()[0].getBinary()).thenReturn(binary);

        final JsonArray result = (JsonArray) this.processor.processProperty(null, property, null, null);

        Assertions.assertEquals("binary content", result.getString(0));
    }

    private Property mockProperty(final int type)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        final Value value = Mockito.mock(Value.class);
        Mockito.when(property.isMultiple()).thenReturn(false);
        Mockito.when(property.getType()).thenReturn(type);
        Mockito.when(property.getValue()).thenReturn(value);
        return property;
    }

    private Property mockMultiProperty(final int type, final int count)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        final Value[] values = new Value[count];
        for (int i = 0; i < count; ++i) {
            values[i] = Mockito.mock(Value.class);
        }
        Mockito.when(property.isMultiple()).thenReturn(true);
        Mockito.when(property.getType()).thenReturn(type);
        Mockito.when(property.getValues()).thenReturn(values);
        return property;
    }
}
