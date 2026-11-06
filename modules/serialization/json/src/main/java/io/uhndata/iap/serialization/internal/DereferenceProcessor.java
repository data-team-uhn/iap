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
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Writes the node a {@code REFERENCE}, {@code WEAKREFERENCE} or {@code PATH} property points to in place of the
 * stored {@code jcr:uuid} or path. The name of this processor is {@code dereference}, and it is enabled by default.
 *
 * <p>
 * A {@code jcr:} property, such as {@code jcr:baseVersion}, leads into version storage, so it is written as the
 * target's path. A multi-valued {@code jcr:} {@code PATH} property is the exception, and is dereferenced.
 * </p>
 *
 * <p>
 * A target this session cannot read is left as stored. In a multi-valued {@code PATH} property, only that value keeps
 * its stored path. In a multi-valued reference, every value keeps its stored {@code jcr:uuid}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class DereferenceProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DereferenceProcessor.class);

    @Override
    public String getName()
    {
        return "dereference";
    }

    @Override
    public int getPriority()
    {
        return 10;
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
        try {
            if (property.isMultiple()) {
                return serializeMultiValuedProperty(property, input, serializeNode);
            } else {
                return serializeSingleValuedProperty(property, input, serializeNode);
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unexpected error while serializing property {} of node {}: {}", property, node,
                e.getMessage());
        }
        return input;
    }

    private JsonValue serializeSingleValuedProperty(final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
        throws RepositoryException
    {
        return switch (property.getType()) {
            case PropertyType.REFERENCE, PropertyType.WEAKREFERENCE, PropertyType.PATH -> {
                JsonValue result = input;
                try {
                    final Node node = property.getNode();
                    if (property.getName().startsWith("jcr:")) {
                        result = Json.createValue(node.getPath());
                    } else {
                        result = serializeNode.apply(node);
                    }
                } catch (RepositoryException e) {
                    // The target cannot be read: leave the value as stored
                }
                yield result;
            }
            default -> input;
        };
    }

    private JsonValue serializeMultiValuedProperty(final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode) throws RepositoryException
    {
        final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
        final String name = property.getName();

        if (property.getType() == PropertyType.REFERENCE || property.getType() == PropertyType.WEAKREFERENCE) {
            for (Value value : property.getValues()) {
                try {
                    final Node node = property.getSession().getNodeByIdentifier(value.getString());
                    if (name.startsWith("jcr:")) {
                        arrayBuilder.add(node.getPath());
                    } else {
                        arrayBuilder.add(serializeNode.apply(node));
                    }
                } catch (RepositoryException e) {
                    return input;
                }
            }
        } else if (property.getType() == PropertyType.PATH) {
            for (Value value : property.getValues()) {
                final String path = value.getString();
                try {
                    final Node node = path.charAt(0) == '/' ? property.getSession().getNode(path)
                        : property.getParent().getNode(path);
                    arrayBuilder.add(serializeNode.apply(node));
                } catch (RepositoryException e) {
                    arrayBuilder.add(path);
                }
            }
        } else {
            return input;
        }
        return arrayBuilder.build();
    }
}
