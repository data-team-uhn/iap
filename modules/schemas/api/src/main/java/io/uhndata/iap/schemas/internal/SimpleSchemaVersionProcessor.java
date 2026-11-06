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
import jakarta.json.JsonValue;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serializes a schema version as its own properties only. Its requirements are left out, and its {@code workflow}
 * stays the {@code jcr:uuid} it is stored as, rather than the workflow version {@code dereference} inlines. The
 * processor is named {@code simple}. Without it, the schema version serializes with its requirements.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class SimpleSchemaVersionProcessor implements ResourceJsonProcessor
{
    private static final String SCHEMA_VERSION_TYPE = "sch:SchemaVersion";

    private static final String WORKFLOW_PROPERTY = "workflow";

    @Override
    public String getName()
    {
        return "simple";
    }

    @Override
    public int getPriority()
    {
        // After the general `simple` (25), and after `dereference` (10), whose inlining of the workflow this undoes
        return 50;
    }

    @Override
    public JsonValue processProperty(final Node node, final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (isSchemaVersion(node) && WORKFLOW_PROPERTY.equals(property.getName())) {
                return Json.createValue(property.getString());
            }
        } catch (RepositoryException e) {
            // A property that cannot be read is left as it was found, as `dereference` leaves it
        }
        return input;
    }

    @Override
    public JsonValue processChild(final Node node, final Node child, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        // The requirements, and anything else stored under a version
        return isSchemaVersion(node) ? null : input;
    }

    private boolean isSchemaVersion(final Node node)
    {
        try {
            return node != null && node.isNodeType(SCHEMA_VERSION_TYPE);
        } catch (RepositoryException e) {
            // A node whose type cannot be read is not treated as a schema version
            return false;
        }
    }
}
