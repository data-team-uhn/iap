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

import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.SchemasHomepage;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Leaves out of a schema listing what takes no new submissions: a schema that carries the {@code retired} lifecycle
 * tag, and a version that does not carry {@code active}. Creating a submission refuses both. The processor is named
 * {@code active} and is on by default, and {@code -active} turns it off to list everything.
 *
 * <p>It leaves out children only, so a retired schema or version requested by its own path still serializes.</p>
 *
 * <p>Only the tags placed on the child itself count. A schema without lifecycle tags is kept, and a version without
 * them is left out. A version also inherits its schema's retirement, but a retired schema is already left out of any
 * listing that would hold its versions.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class ActiveSchemasProcessor implements ResourceJsonProcessor
{
    /** The resource types whose serialization this applies to: a listing of schemas, or of a schema's versions. */
    private static final List<String> SERIALIZED_TREES =
        List.of(SchemasHomepage.RESOURCE_TYPE, Schema.RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE);

    private static final String PROCESSOR_NAME = "active";

    private static final String SCHEMA_TYPE = "sch:Schema";

    private static final String VERSION_TYPE = "sch:SchemaVersion";

    /** The property holding the names of the tags placed on a node. */
    private static final String TAGS_PROPERTY = "tags";

    private static final String ACTIVE_TAG = "active";

    private static final String RETIRED_TAG = "retired";

    @Override
    public String getName()
    {
        return PROCESSOR_NAME;
    }

    @Override
    public int getPriority()
    {
        // After `deep` (10), which serializes the children, or `deep` would put a discarded child back
        return 20;
    }

    @Override
    public boolean canProcess(@NotNull final Resource resource)
    {
        return SERIALIZED_TREES.stream().anyMatch(resource::isResourceType);
    }

    @Override
    public boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        return true;
    }

    @Override
    @Nullable
    public JsonValue processChild(@NotNull final Node node, @NotNull final Node child,
        @Nullable final JsonValue input, @NotNull final Function<Node, JsonValue> serializeNode)
    {
        return input == null || isOffered(child) ? input : null;
    }

    /**
     * Whether a child belongs in the serialization. A schema is left out if it is retired, and a version unless it
     * is active. Anything else is kept.
     *
     * <p>A child whose type or tags cannot be read is kept. A retired schema shown by mistake costs a refusal when
     * a submission is created against it. An open schema hidden by mistake cannot be chosen at all.</p>
     *
     * @param child the child node being serialized
     * @return {@code true} if it belongs in the serialization
     */
    private static boolean isOffered(final Node child)
    {
        try {
            if (child.isNodeType(SCHEMA_TYPE)) {
                return !hasOwnTag(child, RETIRED_TAG);
            }
            if (child.isNodeType(VERSION_TYPE)) {
                return hasOwnTag(child, ACTIVE_TAG);
            }
            return true;
        } catch (final RepositoryException e) {
            return true;
        }
    }

    /**
     * Whether a tag is placed on a node itself, read from its {@code tags} property, whether it holds one value or
     * several.
     *
     * @param node the node to look at
     * @param tag the tag name
     * @return {@code true} if the node's own tags include it
     * @throws RepositoryException when the property cannot be read
     */
    private static boolean hasOwnTag(final Node node, final String tag) throws RepositoryException
    {
        if (!node.hasProperty(TAGS_PROPERTY)) {
            return false;
        }
        final Property tags = node.getProperty(TAGS_PROPERTY);
        if (!tags.isMultiple()) {
            return tag.equals(tags.getString());
        }
        for (final Value value : tags.getValues()) {
            if (tag.equals(value.getString())) {
                return true;
            }
        }
        return false;
    }
}
