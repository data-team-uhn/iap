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
import javax.jcr.RepositoryException;

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
 * Leaves retired schemas and schema versions out of the schema tree's serialization. The name of this processor is
 * {@code active}, and it is enabled by default; ask for {@code -active} to see everything.
 *
 * <p>What it is for: anyone choosing what to submit against reads this tree, and a retired schema is not something
 * they may choose — the server refuses a submission against one — so listing it only offers a choice that will be
 * taken away again. Filtering it here rather than in each reader means the rule is stated once, and on the side that
 * actually knows it.</p>
 *
 * <p>It filters <em>children</em>, so a retired schema requested directly still serializes: whoever asked for it by
 * path already knows which one they want, and somebody has to be able to read one in order to bring it back. What
 * disappears is the retired schema in a listing of schemas, and the retired version in a listing of versions.</p>
 *
 * <p>Absent counts as retired, because that is what the node type says: {@code active} defaults to {@code false},
 * so a schema is something someone deliberately opens rather than something that arrives open.</p>
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

    /** The node types whose retirement this hides; anything else a schema holds is none of its business. */
    private static final List<String> RETIRABLE = List.of("sch:Schema", "sch:SchemaVersion");

    private static final String ACTIVE = "active";

    @Override
    public String getName()
    {
        return ACTIVE;
    }

    @Override
    public int getPriority()
    {
        // After `deep` (10), which is what turns a child into JSON in the first place: discarding it has to be the
        // later word, or the child would be serialized back in after being left out
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
     * Whether a child belongs in the serialization: everything that is not a schema or a version does, and those do
     * only while they are active.
     *
     * <p>A child that cannot be read is kept. Hiding a schema that is in fact open would leave a submitter with
     * nothing to choose and no way to tell why, whereas keeping a retired one costs at most a refusal from the
     * server, which enforces this properly rather than relying on what a listing showed.</p>
     *
     * @param child the child node being serialized
     * @return {@code true} if it should appear
     */
    private static boolean isOffered(final Node child)
    {
        try {
            for (final String type : RETIRABLE) {
                if (child.isNodeType(type)) {
                    return child.hasProperty(ACTIVE) && child.getProperty(ACTIVE).getBoolean();
                }
            }
            return true;
        } catch (final RepositoryException e) {
            return true;
        }
    }
}
