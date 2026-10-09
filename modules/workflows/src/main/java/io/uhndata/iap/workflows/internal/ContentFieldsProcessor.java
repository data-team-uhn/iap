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
package io.uhndata.iap.workflows.internal;

import java.util.List;
import java.util.Objects;
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
import io.uhndata.iap.workflows.internal.handlers.ContentFields;
import io.uhndata.iap.workflows.internal.handlers.UpdateContentHandler;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Adds {@code @fields} to content an update workflow would change: the fields the requesting user's {@code update}
 * event would let a patch change there, each with its {@code name}, {@code label}, {@code kind}, whether it holds
 * several values, is {@code mandatory} or {@code multiline}, the {@code default} new content starts with, and what
 * else the activity says about it (see {@link ContentFields}). It is read from the
 * {@link UpdateContentHandler} activity of the workflow that would run, so an editor offers exactly what the update
 * accepts and keeps no list of its own. When that workflow has a {@link WorkflowVersion#getNotice notice}, it is added
 * as {@code @notice}: what the update allows, in words. The name of this processor is {@code fields}; it is off by
 * default.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceJsonProcessor.class)
public class ContentFieldsProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentFieldsProcessor.class);

    private static final String UPDATE_EVENT = "update";

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
            if (!node.isNodeType("data:Content")) {
                return;
            }
            resource = Objects.requireNonNull(this.resolver.get().getResource(node.getPath()),
                "A node being serialized is visible to the session serializing it");
            final WorkflowVersion update = this.engine.findApplicableWorkflow(resource, UPDATE_EVENT);
            if (update != null) {
                final List<ContentFields.Description> described =
                    update.getActivities(UpdateContentHandler.HANDLER_NAME).stream()
                        .flatMap(activity -> ContentFields.describedBy(activity).stream())
                        .toList();
                json.add("@fields", describe(ContentFields.editable(described, node)));
                addIfSet(json, "@notice", update.getNotice());
            }
        } catch (final RepositoryException | WorkflowException | RuntimeException e) {
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
     * The fields as the serialization lists them.
     *
     * @param fields the fields
     * @return their descriptions
     */
    static JsonArrayBuilder describe(final List<ContentFields.Field> fields) throws RepositoryException
    {
        // A loop rather than a stream: a field reading its defaults may throw
        final JsonArrayBuilder described = Json.createArrayBuilder();
        for (final ContentFields.Field field : fields) {
            described.add(field.toJson());
        }
        return described;
    }

    /**
     * Adds an optional text to a description.
     *
     * @param json the description
     * @param name the key
     * @param value the text, left out when not set
     */
    static void addIfSet(final JsonObjectBuilder json, final String name, final String value)
    {
        if (value != null) {
            json.add(name, value);
        }
    }
}
