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
package io.uhndata.iap.schemas.editing.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.tags.models.Taggable;

/**
 * Builds schemas in an Oak-backed mock repository. The tags service does not run under sling-mock, so the
 * Taggable view is backed by the {@code tags} properties themselves, read live so that what a handler writes is
 * what the models see next, with {@code retired} inherited from ancestors.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SchemaFixture
{
    private final SlingContext context;

    /**
     * Prepares the context: the models, the Taggable view, and an empty {@code /Schemas}.
     *
     * @param context an Oak-backed context
     * @throws PersistenceException when the homepage cannot be created
     */
    SchemaFixture(final SlingContext context) throws PersistenceException
    {
        this.context = context;
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Question.class, AnswerOption.class);
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            Mockito.when(taggable.hasOwnTag(Mockito.anyString()))
                .thenAnswer(call -> tagsOf(resource).contains(call.getArgument(0, String.class)));
            Mockito.when(taggable.hasTag(Mockito.anyString())).thenAnswer(call -> {
                for (Resource current = resource; current != null; current = current.getParent()) {
                    if (tagsOf(current).contains(call.getArgument(0, String.class))) {
                        return true;
                    }
                }
                return false;
            });
            return taggable;
        });
        context.resourceResolver().create(context.resourceResolver().getResource("/"), "Schemas",
            Map.of("jcr:primaryType", "sch:SchemasHomepage"));
        context.resourceResolver().commit();
    }

    private static List<String> tagsOf(final Resource resource)
    {
        return Arrays.asList(resource.getValueMap().get("tags", new String[0]));
    }

    /**
     * Creates a schema.
     *
     * @param name its node name
     * @param tags its tags
     * @return the schema
     * @throws PersistenceException when it cannot be created
     */
    Resource schema(final String name, final String... tags) throws PersistenceException
    {
        return create("/Schemas", name, "sch:Schema", Map.of("title", "The " + name + " schema"), tags);
    }

    /**
     * Creates a version under a schema.
     *
     * @param schema the schema
     * @param name its node name
     * @param tags its tags, e.g. its lifecycle state
     * @return the version
     * @throws PersistenceException when it cannot be created
     */
    Resource version(final Resource schema, final String name, final String... tags) throws PersistenceException
    {
        return create(schema.getPath(), name, "sch:SchemaVersion", Map.of("version", name), tags);
    }

    /**
     * Creates any node and commits it.
     *
     * @param parent the parent's path
     * @param name the node name
     * @param primaryType the node type
     * @param properties its other properties
     * @param tags its tags
     * @return the node
     * @throws PersistenceException when it cannot be created
     */
    Resource create(final String parent, final String name, final String primaryType,
        final Map<String, Object> properties, final String... tags) throws PersistenceException
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("jcr:primaryType", primaryType);
        if (tags.length > 0) {
            all.put("tags", tags);
        }
        final Resource created = this.context.resourceResolver().create(
            this.context.resourceResolver().getResource(parent), name, all);
        this.context.resourceResolver().commit();
        return created;
    }

    /**
     * Re-reads a resource after a handler changed it.
     *
     * @param path the resource's path
     * @return the resource, or {@code null} if it is gone
     */
    Resource get(final String path)
    {
        return this.context.resourceResolver().getResource(path);
    }
}
