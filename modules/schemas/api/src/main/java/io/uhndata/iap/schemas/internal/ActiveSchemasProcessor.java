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
 * Leaves closed schemas and schema versions out of the schema tree's serialization: a schema carrying the
 * {@code retired} lifecycle tag, and a version not carrying {@code active}. The name of this processor is
 * {@code active}, and it is enabled by default; ask for {@code -active} to see everything.
 *
 * <p>Anyone choosing what to submit against reads this tree, and a retired schema is not something they may
 * choose: the server refuses a submission against one. Listing it only offers a choice that will be taken away
 * again. Filtering here rather than in each reader states the rule once, on the side that knows it.</p>
 *
 * <p>It filters <em>children</em>, so a retired schema requested directly still serializes. Whoever asked for
 * it by path already knows which one they want, and somebody has to be able to read one in order to bring it
 * back. What disappears is the retired schema in a listing, and the retired version in a listing of versions.</p>
 *
 * <p>Only the tags placed on the child itself count. A schema is open unless retired, so one without lifecycle
 * tags is kept; a version is closed until made active, so one without them is left out. A version also inherits
 * its schema's retirement, but a retired schema is already gone from any listing that would hold it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class ActiveSchemasProcessor implements ResourceJsonProcessor
{
    /** Where this applies: a listing of schemas, or of one schema's versions. */
    private static final List<String> SERIALIZED_TREES =
        List.of(SchemasHomepage.RESOURCE_TYPE, Schema.RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE);

    private static final String NAME = "active";

    private static final String SCHEMA_TYPE = "sch:Schema";

    private static final String VERSION_TYPE = "sch:SchemaVersion";

    /** The property holding the names of the tags placed on a node. */
    private static final String TAGS_PROPERTY = "tags";

    private static final String ACTIVE_TAG = "active";

    private static final String RETIRED_TAG = "retired";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public int getPriority()
    {
        // After `deep` (10), which is what turns a child into JSON in the first place.
        // Discarding it has to be the later word, or the child would be serialized back in after being left out.
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
     * Whether a child belongs in the serialization. Everything that is not a schema or a version is not filtered.
     * A schema is discarded if it is retired, and a version unless it is active.
     *
     * <p>A child that cannot be read is kept. Hiding a schema that is in fact open would leave a submitter
     * with nothing to choose and no way to tell why. Keeping a retired one costs at most a refusal from the
     * server, which enforces this properly rather than relying on what a listing showed.</p>
     *
     * @param child the child node being serialized
     * @return {@code true} if it should appear
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
     * Whether a tag is placed on a node itself, read from its {@code tags} property, multivalued or not.
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
