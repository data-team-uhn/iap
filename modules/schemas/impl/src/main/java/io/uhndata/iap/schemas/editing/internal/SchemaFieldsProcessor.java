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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;

/**
 * The {@code fields} serialization processor: adds {@code @fields} to each schema and schema version serialized,
 * listing the fields the requesting user's {@code update} event would change on it right now, with what an
 * editor needs to show them. Which fields those are is the configuration of the update workflow that would run,
 * so the editor never lists fields of its own. Off by default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceJsonProcessor.class)
public class SchemaFieldsProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaFieldsProcessor.class);

    private static final List<String> EDITED_TYPES = List.of("sch:Schema", "sch:SchemaVersion");

    private static final String UPDATE_EVENT = "update";

    /** The requesting user's resolver, for the serialization running on this thread. */
    private final ThreadLocal<ResourceResolver> resolver = new ThreadLocal<>();

    @Reference
    private WorkflowEngine engine;

    @Override
    public String getName()
    {
        return "fields";
    }

    @Override
    public int getPriority()
    {
        return 50;
    }

    @Override
    public void start(final Resource resource)
    {
        this.resolver.set(resource.getResourceResolver());
    }

    @Override
    public void leave(final Node node, final JsonObjectBuilder json, final Function<Node, JsonValue> serializeNode)
    {
        Resource resource = null;
        try {
            if (EDITED_TYPES.stream().noneMatch(type -> isNodeType(node, type))) {
                return;
            }
            resource = Objects.requireNonNull(this.resolver.get().getResource(node.getPath()),
                "A node being serialized is visible to the session serializing it");
            final List<String> allowed = this.engine.inspectWorkflow(resource, UPDATE_EVENT, version -> version
                .getFlowNodes().stream()
                .filter(Activity.class::isInstance)
                .map(Activity.class::cast)
                .filter(activity -> UpdateSchemaContentHandler.HANDLER_NAME.equals(activity.getHandler()))
                .flatMap(activity -> SchemaFields.allowedBy(activity).stream())
                .toList());
            json.add("@fields", describe(resource.getResourceType(), allowed == null ? List.of() : allowed));
        } catch (final RepositoryException | WorkflowException e) {
            // Nothing editable is the safe answer; the serialization itself must not fail over it
            LOGGER.error("Could not list the fields editable on {}: {}", node, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "leave").about(resource));
        }
    }

    @Override
    public void end(final Resource resource)
    {
        this.resolver.remove();
    }

    /**
     * The fields an editor shows, in the order the content declares them.
     *
     * @param resourceType the content's resource type
     * @param allowed the fields the update would change
     * @return one object per field
     */
    private static JsonArrayBuilder describe(final String resourceType, final List<String> allowed)
    {
        final JsonArrayBuilder fields = Json.createArrayBuilder();
        allowed.stream()
            .map(name -> SchemaFields.find(resourceType, name))
            .flatMap(Optional::stream)
            .forEach(field -> fields.add(Json.createObjectBuilder()
                .add("name", field.name())
                .add("label", field.label())
                .add("kind", field.kind().name().toLowerCase(Locale.ROOT))
                .add("mandatory", field.mandatory())
                .add("multiline", field.multiline())));
        return fields;
    }

    private static boolean isNodeType(final Node node, final String type)
    {
        try {
            return node.isNodeType(type);
        } catch (final RepositoryException e) {
            return false;
        }
    }
}
