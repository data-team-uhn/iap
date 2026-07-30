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
 * AdapterFactory that converts Apache Sling resources to JsonObjects. This is just a shell, the actual implementation
 * of the serialization process is implemented by implementations of the {@link ResourceJsonProcessor} service. To
 * configure the serialization process, include serializer names in the resource selectors. This can be accomplished
 * by appending them in the request URL for a resource, for example
 * <code>http://server.example/path/to/resource.deep.simple.json</code>, or by appending them in the resource path
 * when using {@code resourceResolver.resolve}, for example
 * {@code resourceResolver.resolve("/path/to/resource.deep.simple")}. A few processors are
 * {@link ResourceJsonProcessor#isEnabledByDefault(Resource) enabled by default}, for example the {@code properties},
 * {@code identify}, and {@code dereference} processors; to disable them, use their name prefixed by {@code -} in the
 * selectors, e.g. {@code /path/to/resource.-dereference.json}.
 *
 * <p>
 * How many levels of descendants to serialize can be limited with a numeric selector, using the same convention as
 * Sling's default JSON renderer: {@code .0.json} serializes just the resource itself, {@code .1.json} the resource
 * and its direct children, and {@code .infinity.json} the whole subtree. Nodes past the requested depth, just like
 * nodes that are already being serialized higher up in the JSON, are included only as their path. Without a numeric
 * selector the depth is unlimited, so {@code .deep.json} still serializes the whole subtree.
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

    /** A list of all available processors. */
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
        // The state of this serialization: the processors to invoke, the requested depth, and the nodes processed so
        // far down the stack.
        final SerializationContext context = new SerializationContext(resource);

        start(resource, context);
        final Node node = resource.adaptTo(Node.class);
        JsonValue result = serializeNode(node, context);
        end(resource, context);
        if (result != null) {
            return type.cast(result);
        }
        return null;
    }

    /**
     * Serializes a Node into a JSON value. Usually this will be a JSON object listing its items, but to avoid
     * recursion, or to stay within the requested depth, it is also possible to be just the node's path as a simple
     * string.
     *
     * @param node the node to serialize
     * @param context the state of the current serialization
     * @return a JSON value, either a JsonObject or a JsonString
     */
    private JsonValue serializeNode(final Node node, final SerializationContext context)
    {
        if (node == null) {
            return null;
        }

        String path = null;
        try {
            path = node.getPath();
            // Serializing a node that is already being serialized higher up the stack would lead to infinite
            // recursion, and a node deeper than the requested depth isn't wanted; either way, only its path is
            // included in the output.
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
            // The node was entered only if its path could be retrieved
            if (path != null) {
                context.leave();
            }
        }
        return null;
    }

    /**
     * Prepare the serialization of a resource by invoking {@link ResourceJsonProcessor#start} in all enabled
     * processors.
     *
     * @param resource the resource being serialized
     * @param context the state of the current serialization
     */
    private void start(final Resource resource, final SerializationContext context)
    {
        context.processors.forEach(p -> p.start(resource));
    }

    /**
     * Prepare the serialization of a node by invoking {@link ResourceJsonProcessor#enter} in all enabled processors.
     *
     * @param node the node to serialize
     * @param json the JSON being built
     * @param context the state of the current serialization
     */
    private void enterNode(final Node node, final JsonObjectBuilder json, final SerializationContext context)
    {
        context.processors.forEach(p -> p.enter(node, json, n -> serializeNode(n, context)));
    }

    /**
     * Serialize the properties of a node into a {@code JsonObjectBuilder} by invoking
     * {@link ResourceJsonProcessor#processProperty} in all enabled processors.
     *
     * @param node the node to serialize
     * @param json the JSON being built
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
     * Serialize the children of a node into a {@code JsonObjectBuilder} by invoking
     * {@link ResourceJsonProcessor#processChild} in all enabled processors.
     *
     * @param node the node to serialize
     * @param json the JSON being built
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
     * Further enhance the JSON representing a node after all its properties and children have been serialized by
     * invoking {@link ResourceJsonProcessor#leave} in all enabled processors.
     *
     * @param node the node to serialize
     * @param json the JSON being built
     * @param context the state of the current serialization
     */
    private void leaveNode(final Node node, final JsonObjectBuilder json, final SerializationContext context)
    {
        context.processors.forEach(p -> p.leave(node, json, n -> serializeNode(n, context)));
    }

    /**
     * Clean up after the serialization of a resource by invoking {@link ResourceJsonProcessor#end} in all enabled
     * processors.
     *
     * @param resource the resource that was serialized
     * @param context the state of the current serialization
     */
    private void end(final Resource resource, final SerializationContext context)
    {
        context.processors.forEach(p -> p.end(resource));
    }

    /**
     * Compute the list of enabled processors using the resource's type and selectors.
     *
     * @param resource the resource to serialize
     * @return the list of enabled processors, sorted in ascending order of their priority
     */
    private List<ResourceJsonProcessor> setupProcessors(final Resource resource)
    {
        // Compute the list of requested processor names:
        // These are enabled by default
        final List<String> defaults = this.allProcessors.stream().filter(p -> p.isEnabledByDefault(resource))
            .map(ResourceJsonProcessor::getName).collect(Collectors.toList());
        // These have been requested
        final List<String> requestedProcessors = new ArrayList<>(
            SelectorUtils.parseSelectors(resource.getResourceMetadata().getResolutionPathInfo()));
        // Add the defaults, if not already selected and not explicitly excluded
        for (String def : defaults) {
            if (!requestedProcessors.contains(def) && !requestedProcessors.contains("-" + def)) {
                requestedProcessors.add(def);
            }
        }

        // Build the enabled list using the requested names
        final List<ResourceJsonProcessor> enabled = this.allProcessors.stream()
            .filter(p -> requestedProcessors.contains(p.getName()))
            .filter(p -> p.canProcess(resource))
            .collect(Collectors.toList());
        enabled.sort((o1, o2) -> o1.getPriority() - o2.getPriority());

        return enabled;
    }

    /**
     * The state of one serialization process: which processors are enabled, how deep the serialization may go, and
     * which nodes are currently being serialized higher up in the JSON being built.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private final class SerializationContext
    {
        /** The processors enabled for this serialization, in ascending order of their priority. */
        private final List<ResourceJsonProcessor> processors;

        /** How many levels of descendants to serialize, negative for no limit. */
        private final int maxDepth;

        /** The paths of the nodes whose serialization is in progress, the innermost one first. */
        private final Deque<String> openNodes = new ArrayDeque<>();

        SerializationContext(final Resource resource)
        {
            this.processors = setupProcessors(resource);
            this.maxDepth = SelectorUtils.parseDepth(resource.getResourceMetadata().getResolutionPathInfo())
                .orElse(SelectorUtils.UNLIMITED_DEPTH);
        }

        /**
         * Check if a node is already being serialized higher up in the JSON being built, in which case serializing it
         * again would lead to infinite recursion.
         *
         * @param path the path of the node about to be serialized
         * @return {@code true} if the node's serialization is already in progress
         */
        boolean isBeingSerialized(final String path)
        {
            return this.openNodes.contains(path);
        }

        /**
         * Check if the node about to be serialized is deeper than the requested depth. The topmost node is at depth
         * {@code 0}, the nodes nested in it at depth {@code 1}, and so on.
         *
         * @return {@code true} if the node is past the requested depth
         */
        boolean isTooDeep()
        {
            return this.maxDepth >= 0 && this.openNodes.size() > this.maxDepth;
        }

        /**
         * Record that a node's serialization started.
         *
         * @param path the path of the node
         */
        void enter(final String path)
        {
            this.openNodes.push(path);
        }

        /** Record that the innermost node's serialization ended. */
        void leave()
        {
            this.openNodes.pop();
        }
    }
}
