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
package io.uhndata.iap.utils.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;

import io.uhndata.iap.utils.PrefixTree;

/**
 * Lets a node filed in a {@link PrefixTree} be addressed directly under the tree's root, as
 * {@code <root>/<name>}, without the buckets it is actually stored in.
 *
 * <p>
 * The tree exists so that no single parent holds every node of a kind; that is a storage concern, and there is no
 * reason for it to reach the people reading a URL.
 * </p>
 *
 * <p>
 * <b>The resource handed back is the real one, and it keeps its real path.</b> The short form is an address, not
 * an identity: a resource whose {@code getPath()} disagreed with the node behind it would be a trap, since
 * anything adapting to a {@code Node} would then work on a different path than the one it was given. Everything
 * downstream keeps seeing the storage path; only the URL is short.
 * </p>
 *
 * <p>
 * One instance per tree, through factory configuration. The root is read back from the very
 * {@code provider.root} service property the resource resolver mounts the provider at, so the path this translates
 * under and the path it is registered for cannot drift apart. A deployment adds a tree by adding a configuration:
 * </p>
 *
 * <pre>
 * "io.uhndata.iap.utils.internal.PrefixTreeResourceProvider~submissions": {
 *     "provider.root": "/Submissions",
 *     "provider.mode": "overlay"
 * }
 * </pre>
 *
 * <p>
 * Overlay mode is what keeps this additive: stored paths are always answered by the repository below, so the tree
 * root, the buckets, the nodes at their stored paths and everything inside them resolve exactly as before. Nothing
 * is synthesized either — the resource returned here was read through the repository with the requester's own
 * session, so access control applies to it without this provider reproducing any of it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceProvider.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class PrefixTreeResourceProvider extends ResourceProvider<Object>
{
    /** The root of the tree this instance serves, and the path it is mounted at: the same value, read once. */
    private String root;

    /** {@link #root} plus a slash, which is what a short path has to start with. */
    private String prefix;

    /**
     * Reads back the root this provider was mounted at.
     *
     * @param properties the component's service properties, carrying the {@code provider.root} the resource
     *            resolver uses to decide what this provider is asked about
     */
    @Activate
    void activate(final Map<String, Object> properties)
    {
        this.root = Objects.toString(properties.get(ResourceProvider.PROPERTY_ROOT), "");
        this.prefix = this.root + "/";
    }

    @Override
    public Resource getResource(final ResolveContext<Object> ctx, final String path,
        final ResourceContext resourceContext, final Resource parent)
    {
        final Resource stored = resolveStored(ctx, path, resourceContext, parent);
        if (stored != null) {
            return stored;
        }
        final String name = filedName(path);
        if (name == null) {
            return null;
        }
        // Resolved through the repository below, so what comes back carries its own type, properties, access
        // control and path
        return resolveStored(ctx, PrefixTree.pathFor(this.root, name), resourceContext, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<Resource> listChildren(final ResolveContext<Object> ctx, final Resource parent)
    {
        // Whatever the repository holds there, and nothing of this provider's own
        final ResourceProvider<Object> below = (ResourceProvider<Object>) ctx.getParentResourceProvider();
        final ResolveContext<Object> belowContext = (ResolveContext<Object>) ctx.getParentResolveContext();
        if (below == null || belowContext == null) {
            return null;
        }
        return below.listChildren(belowContext, parent);
    }

    /**
     * The name a path is the short form of, or {@code null} when the path is not one and must be left alone.
     *
     * @param path the absolute path being resolved
     * @return the name to look up in the prefix tree, or {@code null}
     */
    private String filedName(final String path)
    {
        // Without a root there is nothing to be short for. The resolver does not mount a provider that declares no
        // provider.root, so this only guards against a configuration that could never have worked.
        if (this.root.isEmpty() || !path.startsWith(this.prefix)) {
            return null;
        }
        final String name = path.substring(this.prefix.length());
        // Only a direct child of the root is a short form. Anything deeper is either a stored path, which the
        // repository already answered, or a resolution artifact such as the relative `.html` a page script
        // includes — and a segment carrying a dot is the resolver still peeling selectors and an extension off,
        // which must be declined rather than answered with the node again, or resolution recurses.
        if (name.indexOf('/') >= 0 || name.indexOf('.') >= 0) {
            return null;
        }
        // A bucket's name is shorter than anything the tree can file, so a bucket is never read as a filed node
        return name.length() < PrefixTree.MINIMUM_NAME_LENGTH ? null : name;
    }

    /**
     * Looks a path up in whatever provides the repository below this one.
     *
     * @param ctx the context this provider was called with
     * @param path the absolute path to resolve
     * @param resourceContext the resolution context, passed on unchanged
     * @param parent the parent resource, when the resolver already knows it
     * @return the stored resource, or {@code null} when nothing is stored at that path
     */
    @SuppressWarnings("unchecked")
    private static Resource resolveStored(final ResolveContext<Object> ctx, final String path,
        final ResourceContext resourceContext, final Resource parent)
    {
        final ResourceProvider<Object> below = (ResourceProvider<Object>) ctx.getParentResourceProvider();
        final ResolveContext<Object> belowContext = (ResolveContext<Object>) ctx.getParentResolveContext();
        if (below == null || belowContext == null) {
            return null;
        }
        return below.getResource(belowContext, path, resourceContext, parent);
    }
}
