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
 * Serialize a schema version as what identifies it rather than as everything it governs. The name of this processor
 * is {@code simple}.
 *
 * <p>
 * A schema version is a small node with a very large tail: the requirements a submission must fulfill hang under it,
 * and the workflow it freezes is a reference that the {@code dereference} processor inlines whole, BPMN included. A
 * caller that only wants to name the version - which schema, which version, whether it is still open - would receive
 * all of it. This processor keeps the version's own properties and drops that tail: its children are left out, and
 * the workflow stays the identifier it is stored as.
 * </p>
 *
 * <p>
 * Neither is a loss of addressable information: what was dropped is reachable by serializing the schema version
 * itself, which is exactly the request that means "I do want the requirements".
 * </p>
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
        // After the general trimming, and after dereference has already inlined the workflow this undoes
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
            // Following the same rule as the dereferencing this undoes: a property that cannot be read is left as it
            // was found, since a wrong value would be worse than a verbose one
        }
        return input;
    }

    @Override
    public JsonValue processChild(final Node node, final Node child, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        // The requirements, and anything else a deployment nests under a version
        return isSchemaVersion(node) ? null : input;
    }

    private boolean isSchemaVersion(final Node node)
    {
        try {
            return node != null && node.isNodeType(SCHEMA_VERSION_TYPE);
        } catch (RepositoryException e) {
            // A node whose type cannot be read is not one this processor claims to summarize
            return false;
        }
    }
}
