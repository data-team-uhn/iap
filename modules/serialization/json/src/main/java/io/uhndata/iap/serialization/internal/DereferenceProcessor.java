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
 * target's path.
 * </p>
 *
 * <p>
 * A target this session cannot read is left as stored. In a multi-valued property, only that value keeps its stored
 * path or {@code jcr:uuid}, and the others are dereferenced.
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
                JsonValue result = null;
                try {
                    result = writerFor(property, serializeNode).write(property.getNode());
                } catch (RepositoryException e) {
                    // An unreadable target is access control at work, not a fault to record: keep the stored value
                }
                yield result == null ? input : result;
            }
            default -> input;
        };
    }

    private JsonValue serializeMultiValuedProperty(final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode) throws RepositoryException
    {
        return switch (property.getType()) {
            case PropertyType.REFERENCE, PropertyType.WEAKREFERENCE, PropertyType.PATH -> serializeValues(property,
                serializeNode);
            default -> input;
        };
    }

    private JsonValue serializeValues(final Property property, final Function<Node, JsonValue> serializeNode)
        throws RepositoryException
    {
        final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
        final TargetWriter writer = writerFor(property, serializeNode);
        for (Value value : property.getValues()) {
            arrayBuilder.add(serializeValue(property, value.getString(), writer));
        }
        return arrayBuilder.build();
    }

    /** One value of a multi-valued property, dereferenced, or as stored if its target cannot be serialized. */
    private JsonValue serializeValue(final Property property, final String stored, final TargetWriter writer)
    {
        JsonValue serialized = null;
        try {
            serialized = writer.write(resolve(property, stored));
        } catch (RepositoryException e) {
            // An unreadable target is access control at work, not a fault to record: keep the stored value
        }
        return serialized == null ? Json.createValue(stored) : serialized;
    }

    private static Node resolve(final Property property, final String stored) throws RepositoryException
    {
        if (property.getType() != PropertyType.PATH) {
            return property.getSession().getNodeByIdentifier(stored);
        }
        return stored.charAt(0) == '/' ? property.getSession().getNode(stored) : property.getParent().getNode(stored);
    }

    /**
     * How to write the targets of this property. A {@code jcr:} property, such as {@code jcr:baseVersion}, leads into
     * version storage, so its targets are written as paths.
     */
    private static TargetWriter writerFor(final Property property, final Function<Node, JsonValue> serializeNode)
        throws RepositoryException
    {
        if (property.getName().startsWith("jcr:")) {
            return target -> Json.createValue(target.getPath());
        }
        return serializeNode::apply;
    }

    /**
     * Writes the node a value points to.
     *
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface TargetWriter
    {
        JsonValue write(Node target) throws RepositoryException;
    }
}
