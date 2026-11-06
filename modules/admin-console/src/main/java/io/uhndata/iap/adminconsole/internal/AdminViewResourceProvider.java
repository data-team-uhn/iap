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
package io.uhndata.iap.adminconsole.internal;

import java.util.Iterator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Gives the pages of the administration console's tools real URLs, such as {@code /admin/categories}, without a
 * repository node behind each one.
 *
 * <p>
 * A path below {@code /admin} that nothing is stored at resolves to a synthetic resource of the console's own type.
 * Sling renders it with the application shell, and the client-side router decides what the path shows.
 * </p>
 *
 * <p>
 * The provider is mounted on {@code /admin} in overlay mode, and asks the repository first. Stored content, the
 * console node included, resolves with its own type, properties and access control.
 * </p>
 *
 * <p>
 * The provider lists no children, and the resolver asks no other provider for the children of {@code /admin} or of
 * a path below it. Nothing stored there can be listed.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(
    service = ResourceProvider.class,
    property = {
        ResourceProvider.PROPERTY_ROOT + "=" + AdminViewResourceProvider.ADMIN_ROOT,
        ResourceProvider.PROPERTY_MODE + "=" + ResourceProvider.MODE_OVERLAY,
    })
public class AdminViewResourceProvider extends ResourceProvider<Object>
{
    /** The path of the administration console. */
    public static final String ADMIN_ROOT = "/admin";

    /** The resource type of the synthetic resources, which is the console node's own type. */
    static final String VIEW_RESOURCE_TYPE = "app/Homepage";

    @Override
    public Resource getResource(final ResolveContext<Object> ctx, final String path,
        final ResourceContext resourceContext, final Resource parent)
    {
        final Resource stored = resolveStored(ctx, path, resourceContext, parent);
        if (stored != null) {
            return stored;
        }
        if (!path.startsWith(ADMIN_ROOT + "/") || isResolverArtifact(path)) {
            return null;
        }
        // A synthetic resource has no access control of its own. It is served only to a caller who
        // can read the console.
        if (resolveStored(ctx, ADMIN_ROOT, resourceContext, null) == null) {
            return null;
        }
        return new SyntheticResource(ctx.getResourceResolver(), path, VIEW_RESOURCE_TYPE);
    }

    @Override
    public Iterator<Resource> listChildren(final ResolveContext<Object> ctx, final Resource parent)
    {
        // A synthetic resource exists only when asked for by its path
        return null;
    }

    /**
     * Whether the last segment of a path has a dot. Sling asks for such a path before it has taken the extension and
     * selectors off the request path, and it is never a page.
     *
     * <p>
     * Sling offers the whole request path first, then the same path without its last {@code .something}. The script
     * for a request without an extension includes the relative path {@code .html}. So {@code /admin/categories.html}
     * and {@code /admin/categories/.html} both reach this provider before {@code /admin/categories} with the
     * {@code html} extension. A synthetic resource for either has no extension. Its script includes {@code .html}
     * again, until the request fails with a recursion error.
     * </p>
     *
     * <p>
     * A page asked for with an extension still renders. For {@code /admin/categories.edit}, the provider synthesizes
     * {@code /admin/categories}, and Sling renders it with the script for {@code edit}.
     * </p>
     *
     * @param path the absolute path being resolved
     * @return {@code true} if the last segment of the path has a dot
     */
    private boolean isResolverArtifact(final String path)
    {
        return path.indexOf('.', path.lastIndexOf('/')) >= 0;
    }

    /**
     * Looks a path up in the provider below this one, which is the repository.
     *
     * @param ctx the context this provider was called with
     * @param path the absolute path to look up
     * @param resourceContext the resource context of the request, passed on unchanged
     * @param parent the parent resource, or {@code null} if the resolver has not resolved it
     * @return the stored resource, or {@code null} if nothing the caller can read is stored at that path, or if no
     *     provider is below this one
     */
    @SuppressWarnings("unchecked")
    private Resource resolveStored(final ResolveContext<Object> ctx, final String path,
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
