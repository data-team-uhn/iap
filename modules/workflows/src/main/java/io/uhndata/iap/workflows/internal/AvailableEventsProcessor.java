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

import java.util.Objects;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
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

/**
 * The {@code events} serialization processor: adds {@code @events} to each serialized node, listing the
 * {@link WorkflowEngine#getAvailableEvents events} the requesting user could send to it right now. Off by
 * default, since it asks the engine about every content node it serializes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceJsonProcessor.class)
public class AvailableEventsProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AvailableEventsProcessor.class);

    /** Only content is ever the target of an event; access control lists and the like are not. */
    private static final String CONTENT_TYPE = "data:Content";

    /** The requesting user's resolver, for the serialization running on this thread. */
    private final ThreadLocal<ResourceResolver> resolver = new ThreadLocal<>();

    @Reference
    private WorkflowEngine engine;

    @Override
    public String getName()
    {
        return "events";
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
            if (!node.isNodeType(CONTENT_TYPE)) {
                return;
            }
            resource = Objects.requireNonNull(this.resolver.get().getResource(node.getPath()),
                "A node being serialized is visible to the session serializing it");
            json.add("@events", Json.createArrayBuilder(this.engine.getAvailableEvents(resource)));
        } catch (final RepositoryException | WorkflowException | RuntimeException e) {
            // Nothing offered is the safe answer; the serialization itself must not fail over it
            LOGGER.error("Could not list the events available on {}: {}", node, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "leave").about(resource));
        }
    }

    @Override
    public void end(final Resource resource)
    {
        this.resolver.remove();
    }
}
