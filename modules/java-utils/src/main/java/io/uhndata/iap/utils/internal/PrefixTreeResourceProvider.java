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
 * Lets a node filed in a {@link PrefixTree} be addressed by its name alone, as {@code <tree>/by-id/<name>}, without
 * the buckets it is stored in.
 *
 * <p>
 * <b>The resource it returns is the stored one, with its storage path.</b> The short form is an address, not an
 * identity. A resource whose path differed from its node's would send code that adapts it to a {@code Node} to work
 * on another path.
 * </p>
 *
 * <p>
 * <b>It mounts beside the tree, never over it.</b> Sling hands a provider every path under its
 * {@code provider.root}. In overlay mode that includes every write, and this provider has nowhere to store
 * anything. Mounted at {@code <tree>/by-id}, it receives none of the tree's own reads and writes.
 * </p>
 *
 * <p>
 * There is one instance per tree, each from a factory configuration. The tree is the parent of the
 * {@code provider.root}, so one value names both. A deployment adds a tree by adding a configuration:
 * </p>
 *
 * {@snippet lang=json :
 * "io.uhndata.iap.utils.internal.PrefixTreeResourceProvider~submissions": {
 *     "provider.root": "/Submissions/by-id",
 *     "provider.mode": "overlay"
 * }
 * }
 *
 * <p>
 * The resource is read from the provider below, which is the JCR one, with the requester's own session. Access
 * control applies to it as at its storage path.
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

    /** The path this is mounted at, plus a slash: what an address starts with. */
    private String prefix;

    /**
     * Reads the tree this provider addresses from the path it is mounted at.
     *
     * @param properties the component's properties, with the {@code provider.root} that Sling mounts it at
     */
    @Activate
    void activate(final Map<String, Object> properties)
    {
        final String mount = Objects.toString(properties.get(ResourceProvider.PROPERTY_ROOT), "");
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
        return resolveStored(ctx, PrefixTree.pathFor(this.root, name), resourceContext);
    }

    @Override
    public Iterator<Resource> listChildren(final ResolveContext<Object> ctx, final Resource parent)
    {
        // A resource this returns keeps its storage path, so its children are listed there
        return null;
    }

    /**
     * The name a path is an address for.
     *
     * @param path the absolute path being resolved
     * @return the name to look up in the prefix tree; {@code null} if the path is not an address
     */
    private String filedName(final String path)
    {
        // A provider.root of / leaves no tree to address
        if (this.root.isEmpty() || !path.startsWith(this.prefix)) {
            return null;
        }
        final String name = path.substring(this.prefix.length());
        // Only a direct child is an address, and a deeper path is not served. A segment with a dot is Sling trying
        // the path with its selectors and extension still on, and declining it makes Sling try again without them.
        if (name.indexOf('/') >= 0 || name.indexOf('.') >= 0) {
            return null;
        }
        return name.length() < PrefixTree.MINIMUM_NAME_LENGTH ? null : name;
    }

    /**
     * Looks a path up in the provider mounted below this one.
     *
     * @param ctx the context this provider was called with
     * @param path the absolute path to resolve
     * @param resourceContext the resolution context, passed on unchanged
     * @return the stored resource; {@code null} if nothing the requester can read is stored at that path, or there
     *         is no provider below
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
