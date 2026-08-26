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
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;

import io.uhndata.iap.utils.PrefixTree;

/**
 * Lets a node filed in a {@link PrefixTree} be addressed without the buckets it is actually stored in, as
 * {@code <tree>/by-id/<name>}.
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
 * <b>This mounts beside the tree, never over it.</b> A provider is chosen by the longest {@code provider.root}
 * matching the path, for writes as much as for reads — so a provider mounted at the tree's own root would be
 * handed every {@code ResourceResolver.create} under it and, having no storage of its own to create anything in,
 * could only refuse. Mounting one segment to the side leaves the whole real tree to the repository, which is what
 * keeps this addition incapable of breaking anything that was working: nothing resolves differently, and nothing
 * that writes has to know this exists.
 * </p>
 *
 * <p>
 * The side segment is {@value PrefixTree#ADDRESS_SEGMENT}, and it cannot collide with a bucket: a bucket is
 * named after exactly {@link PrefixTree#SEGMENT_LENGTH} characters of the name it files, so any segment of a
 * different length is one the tree can never produce.
 * </p>
 *
 * <p>
 * One instance per tree, through factory configuration. The tree's root is read back as the parent of the very
 * {@code provider.root} this is mounted at, so the path it translates under and the path it is registered for are
 * one value and cannot drift apart. A deployment adds a tree by adding a configuration:
 * </p>
 *
 * <pre>
 * "io.uhndata.iap.utils.internal.PrefixTreeResourceProvider~submissions": {
 *     "provider.root": "/Submissions/by-id",
 *     "provider.mode": "overlay"
 * }
 * </pre>
 *
 * <p>
 * Overlay mode is what gives this the repository below to answer from: nothing is synthesized here, and the
 * resource returned was read through the repository with the requester's own session, so access control applies to
 * it without this provider reproducing any of it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceProvider.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class PrefixTreeResourceProvider extends ResourceProvider<Object>
{
    /** The root of the tree this instance serves: the parent of the path it is mounted at. */
    private String root;

    /** The path this is mounted at, plus a slash, which is what an address has to start with. */
    private String prefix;

    /**
     * Reads back the tree this provider addresses, from the path it was mounted at.
     *
     * @param properties the component's service properties, carrying the {@code provider.root} the resource
     *            resolver uses to decide what this provider is asked about
     */
    @Activate
    void activate(final Map<String, Object> properties)
    {
        final String mount = Objects.toString(properties.get(ResourceProvider.PROPERTY_ROOT), "");
        // Derived rather than configured a second time: one value names both where addresses are served and which
        // tree they are served for, so a deployment cannot point them at different trees
        this.root = Objects.toString(ResourceUtil.getParent(mount), "");
        this.prefix = mount + "/";
    }

    @Override
    public Resource getResource(final ResolveContext<Object> ctx, final String path,
        final ResourceContext resourceContext, final Resource parent)
    {
        final String name = filedName(path);
        if (name == null) {
            return null;
        }
        // Resolved through the repository below, so what comes back carries its own type, properties, access
        // control and path
        return resolveStored(ctx, PrefixTree.pathFor(this.root, name), resourceContext);
    }

    @Override
    public Iterator<Resource> listChildren(final ResolveContext<Object> ctx, final Resource parent)
    {
        // None of this provider's own, and none to pass on either: an address resolves to the real resource at its
        // real path, so whatever asks for its children asks the repository that owns that path, never this
        return null;
    }

    /**
     * The name a path is an address for, or {@code null} when the path is not one and must be left alone.
     *
     * @param path the absolute path being resolved
     * @return the name to look up in the prefix tree, or {@code null}
     */
    private String filedName(final String path)
    {
        // Without a tree there is nothing to address. The resolver does not mount a provider that declares no
        // provider.root, so this only guards against a configuration that could never have worked.
        if (this.root.isEmpty() || !path.startsWith(this.prefix)) {
            return null;
        }
        final String name = path.substring(this.prefix.length());
        // Only a direct child is an address. A segment carrying a dot is the resolver still peeling selectors and
        // an extension off, which must be declined rather than answered with the node again, or resolution
        // recurses; anything deeper belongs to whatever the address resolved to.
        if (name.indexOf('/') >= 0 || name.indexOf('.') >= 0) {
            return null;
        }
        // Shorter than the tree can file is not a name the tree ever produced
        return name.length() < PrefixTree.MINIMUM_NAME_LENGTH ? null : name;
    }

    /**
     * Looks a path up in whatever provides the repository below this one.
     *
     * @param ctx the context this provider was called with
     * @param path the absolute path to resolve
     * @param resourceContext the resolution context, passed on unchanged
     * @return the stored resource, or {@code null} when nothing is stored at that path
     */
    @SuppressWarnings("unchecked")
    private static Resource resolveStored(final ResolveContext<Object> ctx, final String path,
        final ResourceContext resourceContext)
    {
        final ResourceProvider<Object> below = (ResourceProvider<Object>) ctx.getParentResourceProvider();
        final ResolveContext<Object> belowContext = (ResolveContext<Object>) ctx.getParentResolveContext();
        if (below == null || belowContext == null) {
            return null;
        }
        return below.getResource(belowContext, path, resourceContext, null);
    }
}
