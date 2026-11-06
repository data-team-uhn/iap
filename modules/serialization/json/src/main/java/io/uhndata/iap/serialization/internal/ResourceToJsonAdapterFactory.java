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
package io.uhndata.iap.serialization.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.SelectorUtils;

/**
 * Serves a resource as JSON, through {@code adaptTo(JsonObject.class)}. It walks the resource's node, and the
 * {@link ResourceJsonProcessor processors} the selectors enable decide what the JSON holds. The processor interface
 * describes how the selectors choose them.
 *
 * <p>
 * A numeric selector limits how many levels of descendants to serialize, as in Sling's default JSON renderer.
 * {@code .0.json} is the resource alone, {@code .1.json} adds its children, and {@code .infinity.json} or a negative
 * number sets no limit. A node nested deeper is written as its path, and so is a node already being serialized higher
 * up. The limit counts children and referenced nodes alike. Without a numeric selector there is no limit, so
 * {@code .deep.json} serializes the whole subtree.
 * </p>
 *
 * <p>
 * Selectors can also come as {@code selector} query parameters, one per selector:
 * {@code /path/to/resource.json?selector=deep&selector=simple} means {@code .deep.simple.json}. A parameter holds one
 * whole selector, so a dot in it needs no escaping. A path escapes a dot with a backslash, and Jetty refuses a
 * backslash in a path by default. {@code SelectorParameterFilter} reads the parameters.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(
    service = { AdapterFactory.class },
    property = { "adaptables=org.apache.sling.api.resource.Resource", "adapters=jakarta.json.JsonObject" })
public class ResourceToJsonAdapterFactory
    implements AdapterFactory
{
    /** Logging helper. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceToJsonAdapterFactory.class);

    /** Every registered processor. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<ResourceJsonProcessor> allProcessors;

    @Override
    public <A> A getAdapter(final Object adaptable, final Class<A> type)
    {
        if (adaptable == null) {
            return null;
        }
        final Resource resource = (Resource) adaptable;
        final SerializationContext context = new SerializationContext(resource, setupProcessors(resource));

        start(resource, context);
        final JsonValue result;
        try {
            final Node node = resource.adaptTo(Node.class);
            result = serializeNode(node, context);
        } finally {
            // A processor releases in end what it set up in start, even when a processor fails midway
            end(resource, context);
        }
        if (result != null) {
            return type.cast(result);
        }
        return null;
    }

    /**
     * Serializes a node as a JSON object, or as its path if it is past the requested depth or already being serialized
     * higher up.
     *
     * @param node the node to serialize; {@code null} if the resource has no node
     * @param context the state of the current serialization
     * @return the JSON object, or the path as a JSON string; {@code null} if the node is {@code null} or cannot be read
     */
    private JsonValue serializeNode(final Node node, final SerializationContext context)
    {
        if (node == null) {
            return null;
        }

        String path = null;
        try {
            path = node.getPath();
            // Serializing a node again while it is being serialized higher up would recurse forever
            final boolean summarize = context.isBeingSerialized(path) || context.isTooDeep();
            context.enter(path);
            if (!summarize) {
                final JsonObjectBuilder result = Json.createObjectBuilder();
                enterNode(node, result, context);
                processProperties(node, result, context);
                processChildren(node, result, context);
                leaveNode(node, result, context);
                return result.build();
            }
            return Json.createValue(path);
        } catch (RepositoryException e) {
            LOGGER.error("Failed to serialize node [{}] to JSON: {}", node, e.getMessage(), e);
        } finally {
            // The node was entered only if its path could be read
            if (path != null) {
                context.leave();
            }
        }
        return null;
    }

    /**
     * Calls {@link ResourceJsonProcessor#start} on each enabled processor.
     *
     * @param resource the requested resource
     * @param context the state of the current serialization
     */
    private void start(final Resource resource, final SerializationContext context)
    {
        context.processors.forEach(p -> p.start(resource));
    }

    /**
     * Calls {@link ResourceJsonProcessor#enter} on each enabled processor.
     *
     * @param node the node to serialize
     * @param json the node's JSON so far
     * @param context the state of the current serialization
     */
    private void enterNode(final Node node, final JsonObjectBuilder json, final SerializationContext context)
    {
        context.processors.forEach(p -> p.enter(node, json, n -> serializeNode(n, context)));
    }

    /**
     * Writes each property of a node, under the key and with the value that the enabled processors'
     * {@link ResourceJsonProcessor#processPropertyName} and {@link ResourceJsonProcessor#processProperty} give it.
     *
     * @param node the node to serialize
     * @param json the node's JSON so far
     * @param context the state of the current serialization
     * @throws RepositoryException if accessing the repository fails
     */
    private void processProperties(final Node node, final JsonObjectBuilder json, final SerializationContext context)
        throws RepositoryException
    {
        final PropertyIterator properties = node.getProperties();
        while (properties.hasNext()) {
            Property thisProp = properties.nextProperty();
            JsonValue value = null;
            String name = thisProp.getName();
            for (ResourceJsonProcessor p : context.processors) {
                value = p.processProperty(node, thisProp, value, n -> serializeNode(n, context));
                name = p.processPropertyName(node, thisProp, name);
            }
            if (value != null && name != null) {
                json.add(name, value);
            }
        }
    }

    /**
     * Writes each child of a node, with the value that the enabled processors'
     * {@link ResourceJsonProcessor#processChild} give it.
     *
     * @param node the node to serialize
     * @param json the node's JSON so far
     * @param context the state of the current serialization
     * @throws RepositoryException if accessing the repository fails
     */
    private void processChildren(final Node node, final JsonObjectBuilder json, final SerializationContext context)
        throws RepositoryException
    {
        final NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            final Node child = children.nextNode();
            JsonValue value = null;
            for (ResourceJsonProcessor p : context.processors) {
                value = p.processChild(node, child, value, n -> serializeNode(n, context));
            }
            if (value != null) {
                json.add(child.getName(), value);
            }
        }
    }

    /**
     * Calls {@link ResourceJsonProcessor#leave} on each enabled processor.
     *
     * @param node the node to serialize
     * @param json the node's JSON so far
     * @param context the state of the current serialization
     */
    private void leaveNode(final Node node, final JsonObjectBuilder json, final SerializationContext context)
    {
        context.processors.forEach(p -> p.leave(node, json, n -> serializeNode(n, context)));
    }

    /**
     * Calls {@link ResourceJsonProcessor#end} on each enabled processor.
     *
     * @param resource the requested resource
     * @param context the state of the current serialization
     */
    private void end(final Resource resource, final SerializationContext context)
    {
        context.processors.forEach(p -> p.end(resource));
    }

    /**
     * The processors to run for a resource: those its selectors name, and those enabled by default that its selectors
     * do not disable. Of these, only the ones that can process the resource run.
     *
     * @param resource the requested resource
     * @return the processors, in ascending order of priority
     */
    private List<ResourceJsonProcessor> setupProcessors(final Resource resource)
    {
        final List<String> defaults = this.allProcessors.stream().filter(p -> p.isEnabledByDefault(resource))
            .map(ResourceJsonProcessor::getName).collect(Collectors.toList());
        final List<String> requestedProcessors = new ArrayList<>(
            SelectorUtils.parseSelectors(resource.getResourceMetadata().getResolutionPathInfo()));
        for (String def : defaults) {
            if (!requestedProcessors.contains(def) && !requestedProcessors.contains("-" + def)) {
                requestedProcessors.add(def);
            }
        }

        final List<ResourceJsonProcessor> enabled = this.allProcessors.stream()
            .filter(p -> requestedProcessors.contains(p.getName()))
            .filter(p -> p.canProcess(resource))
            .collect(Collectors.toList());
        enabled.sort(Comparator.comparingInt(ResourceJsonProcessor::getPriority));

        return enabled;
    }

    /**
     * The state of one serialization: the processors it runs, how deep it may go, and which nodes it is serializing
     * higher up in the JSON.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class SerializationContext
    {
        /** The processors this serialization runs, in ascending order of priority. */
        private final List<ResourceJsonProcessor> processors;

        /** How many levels of descendants to serialize; negative for no limit. */
        private final int maxDepth;

        /** The paths of the nodes whose serialization is in progress, the innermost one first. */
        private final Deque<String> openNodes = new ArrayDeque<>();

        SerializationContext(final Resource resource, final List<ResourceJsonProcessor> processors)
        {
            this.processors = processors;
            this.maxDepth = SelectorUtils.parseDepth(resource.getResourceMetadata().getResolutionPathInfo())
                .orElse(SelectorUtils.UNLIMITED_DEPTH);
        }

        /**
         * Whether a node is already being serialized higher up in the JSON.
         *
         * @param path the path of the node about to be serialized
         * @return {@code true} if the node's serialization is in progress
         */
        boolean isBeingSerialized(final String path)
        {
            return this.openNodes.contains(path);
        }

        /**
         * Whether the node about to be serialized is past the requested depth. The requested node is at depth
         * {@code 0}, the nodes nested in it at depth {@code 1}, and so on.
         *
         * @return {@code true} if the node is past the requested depth
         */
        boolean isTooDeep()
        {
            return this.maxDepth >= 0 && this.openNodes.size() > this.maxDepth;
        }

        /**
         * Records that a node's serialization started.
         *
         * @param path the path of the node
         */
        void enter(final String path)
        {
            this.openNodes.push(path);
        }

        /** Records that the innermost node's serialization ended. */
        void leave()
        {
            this.openNodes.pop();
        }
    }
}
