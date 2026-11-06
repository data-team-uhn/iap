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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonValue;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.DateUtils;

/**
 * Writes the value of each property as it is stored. The name of this processor is {@code properties}, and it is
 * enabled by default.
 *
 * <p>
 * A {@code LONG} or {@code DOUBLE} is a JSON number, a {@code BOOLEAN} a JSON boolean, and a {@code DATE} an ISO 8601
 * string. Every other type is a string, {@code DECIMAL} included, so a decimal keeps its precision. A {@code BINARY}
 * is a string with one character for each byte, read as ISO 8859-1, so the character's code is the byte's value.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class PropertiesProcessor implements ResourceJsonProcessor
{
    @Override
    public String getName()
    {
        return "properties";
    }

    @Override
    public int getPriority()
    {
        return 0;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        return true;
    }

    @Override
    public JsonValue processProperty(final Node node, final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        if (input != null) {
            return input;
        }
        return serializeProperty(property);
    }

    private JsonValue serializeProperty(final Property property)
    {
        try {
            if (property.isMultiple()) {
                return serializeMultiValuedProperty(property);
            } else {
                return serializeSingleValuedProperty(property);
            }
        } catch (RepositoryException e) {
            // The property cannot be read: write it as a JSON null
        }
        return JsonValue.NULL;
    }

    private JsonValue serializeSingleValuedProperty(final Property property)
        throws RepositoryException
    {
        final Value value = property.getValue();

        return switch (property.getType()) {
            case PropertyType.BINARY -> serializeInputStream(value.getBinary().getStream());
            case PropertyType.BOOLEAN -> value.getBoolean() ? JsonValue.TRUE : JsonValue.FALSE;
            case PropertyType.DATE -> serializeDate(value.getDate());
            case PropertyType.DOUBLE -> Json.createValue(value.getDouble());
            case PropertyType.LONG -> Json.createValue(value.getLong());
            default -> Json.createValue(value.getString());
        };
    }

    private JsonValue serializeMultiValuedProperty(final Property property)
        throws RepositoryException
    {
        final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

        for (Value value : property.getValues()) {
            switch (property.getType()) {
                case PropertyType.BINARY -> arrayBuilder.add(serializeInputStream(value.getBinary().getStream()));
                case PropertyType.BOOLEAN -> arrayBuilder.add(value.getBoolean());
                case PropertyType.DATE -> arrayBuilder.add(serializeDate(value.getDate()));
                case PropertyType.DOUBLE -> arrayBuilder.add(value.getDouble());
                case PropertyType.LONG -> arrayBuilder.add(value.getLong());
                default -> arrayBuilder.add(value.getString());
            }
        }
        return arrayBuilder.build();
    }

    private JsonValue serializeDate(final Calendar value)
    {
        return Json.createValue(DateUtils.toString(value));
    }

    private JsonValue serializeInputStream(final InputStream value)
    {
        try {
            return Json.createValue(IOUtils.toString(value, StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            // The binary cannot be read: write it as a JSON null
        }
        return JsonValue.NULL;
    }
}
