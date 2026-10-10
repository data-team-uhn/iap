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
import javax.jcr.nodetype.NodeTypeManager;

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
import io.uhndata.iap.workflows.internal.handlers.ContentTypes;
import io.uhndata.iap.workflows.internal.handlers.CreateContentHandler;
import io.uhndata.iap.workflows.internal.handlers.UpdateContentHandler;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Adds {@code @creatable} to content a create workflow would add to: each type of content the requesting user's
 * {@code create} event would make there, with its {@code type}, its {@code label}, and the {@code fields} it starts
 * with, described as {@code @fields} describes them, the {@code defaultName} it takes when nothing it says makes a
 * name, whether it is {@code named}, that is, takes a name of its own, and if so the {@code namePattern} such a name
 * must match and the {@code nameHint} that says so, when the workflow has them. It is read from the
 * {@link CreateContentHandler} and
 * {@link UpdateContentHandler} activities of the workflow that would run, so an editor offers exactly what the
 * workflow creates, and can suggest a name the workflow would accept. The name of this processor is {@code creatable}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceJsonProcessor.class)
public class CreatableContentProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CreatableContentProcessor.class);

    private static final String CREATE_EVENT = "create";

    private final ThreadLocal<ResourceResolver> resolver = new ThreadLocal<>();

    @Reference
    private WorkflowEngine engine;

    /**
     * What a create workflow offers: the types its create task lists, and the fields its update task fills in.
     *
     * @param types the types listed
     * @param fields the fields listed
     * @version $Id$
     * @since 0.1.0
     */
    private record Offer(List<ContentTypes.Type> types, List<ContentFields.Description> fields)
    {
    }

    @Override
    public String getName()
    {
        return "creatable";
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
            final WorkflowVersion create = this.engine.findApplicableWorkflow(resource, CREATE_EVENT);
            if (create != null) {
                final Offer offer = offer(create);
                json.add("@creatable", describe(ContentTypes.accepted(offer.types(), node), offer, node));
            }
        } catch (final RepositoryException | WorkflowException | RuntimeException e) {
            // Nothing creatable is the safe answer; the serialization itself must not fail over it
            LOGGER.error("Could not list what may be created in {}: {}", node, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "leave").about(resource));
        }
    }

    @Override
    public void end(final Resource resource)
    {
        this.resolver.remove();
    }

    /**
     * What a create workflow offers.
     *
     * @param version the workflow version that would run
     * @return the types its create tasks list and the fields its update tasks list
     */
    private static Offer offer(final WorkflowVersion version)
    {
        return new Offer(
            version.getActivities(CreateContentHandler.HANDLER_NAME).stream()
                .flatMap(activity -> ContentTypes.listedBy(activity).stream())
                .toList(),
            version.getActivities(UpdateContentHandler.HANDLER_NAME).stream()
                .flatMap(activity -> ContentFields.describedBy(activity).stream())
                .toList());
    }

    /**
     * The types as the serialization lists them.
     *
     * @param types the types that may be created
     * @param offer what the workflow offers
     * @param parent the node they would be created in
     * @return their descriptions
     * @throws RepositoryException when a type cannot be read
     */
    private static JsonArrayBuilder describe(final List<ContentTypes.Type> types, final Offer offer,
        final Node parent) throws RepositoryException
    {
        final NodeTypeManager nodeTypes = parent.getSession().getWorkspace().getNodeTypeManager();
        final JsonArrayBuilder described = Json.createArrayBuilder();
        for (final ContentTypes.Type type : types) {
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add("type", type.nodeType())
                .add("label", type.label())
                .add("defaultName", type.defaultName())
                .add("named", type.named())
                .add("fields", ContentFieldsProcessor.describe(
                    ContentFields.editable(offer.fields(), List.of(nodeTypes.getNodeType(type.nodeType())))));
            if (type.named()) {
                ContentFieldsProcessor.addIfSet(json, "namePattern", type.namePattern());
                ContentFieldsProcessor.addIfSet(json, "nameHint", type.nameHint());
            }
            described.add(json);
        }
        return described;
    }
}
