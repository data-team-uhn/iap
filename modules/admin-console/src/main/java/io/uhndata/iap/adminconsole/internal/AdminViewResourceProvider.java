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
 * Serves every path below the administration console from the console itself, so that a tool's pages can have real
 * URLs, like {@code /admin/groups/reviewers}, without a repository node behind each one.
 *
 * <p>
 * This provider hands back a synthetic resource of the same type as the admin console for anything below it, which the
 * usual script resolution then renders as the application shell, leaving the client-side router to decide what the path
 * means. Registered for {@code /admin} in overlay mode, so real content still wins: the console node itself, and
 * anything genuinely stored beneath it, resolves through the repository as before, with its own access control.
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
    /** The administration console's own path; everything below it is served from it. */
    public static final String ADMIN_ROOT = "/admin";

    /**
     * The type the synthesized resources carry, the same one the console node itself has, so that
     * they render through the very same script.
     */
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
        // A synthetic resource carries no access control of its own, so it borrows the console's:
        // no readable console, no pages below it. Without this a caller who cannot see /admin
        // would still be handed the shell for /admin/anything, which is a wider door than the
        // repository nodes these replace.
        if (resolveStored(ctx, ADMIN_ROOT, resourceContext, null) == null) {
            return null;
        }
        return new SyntheticResource(ctx.getResourceResolver(), path, VIEW_RESOURCE_TYPE);
    }

    @Override
    public Iterator<Resource> listChildren(final ResolveContext<Object> ctx, final Resource parent)
    {
        // These resources exist only when asked for by name; there is no set of them to enumerate,
        // and claiming otherwise would make the console's children unlistable rather than merely
        // undefined.
        return null;
    }

    /**
     * Whether the last segment of a path carries a dot, which means the resolver is still peeling
     * an extension (or selectors) off it rather than asking for a view.
     *
     * <p>
     * Refusing these is what keeps the shell renderable. Resolution offers the whole request path
     * first and only then the same path with the trailing {@code .something} removed, and the
     * extension-less shell script includes the relative path {@code .html} to render its own HTML
     * form -- so both {@code /admin/users.html} and {@code /admin/users/.html} are put to this
     * provider on the way to {@code /admin/users} plus an {@code html} extension. Synthesizing
     * them instead would answer with a resource that has no extension, whose script includes
     * {@code .html} again, and so on until the request dies of recursion. A view URL never
     * contains a dot in its last segment, so there is nothing to lose by declining.
     * </p>
     *
     * @param path the absolute path being resolved
     * @return {@code true} when the path is an artifact of resolution rather than a view
     */
    private boolean isResolverArtifact(final String path)
    {
        return path.indexOf('.', path.lastIndexOf('/')) >= 0;
    }

    /**
     * Looks the path up in whatever provides the repository below this one, so that real content
     * keeps its own resource type, properties and access control.
     *
     * @param ctx the context this provider was called with
     * @param path the absolute path being resolved
     * @param resourceContext the resolution context to pass on unchanged
     * @param parent the parent resource, when the resolver already knows it
     * @return the stored resource, or {@code null} when nothing is stored at that path
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
