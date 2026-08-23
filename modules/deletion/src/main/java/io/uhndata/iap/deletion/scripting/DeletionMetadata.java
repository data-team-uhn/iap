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
package io.uhndata.iap.deletion.scripting;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.script.Bindings;

import jakarta.servlet.RequestDispatcher;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.internal.DeletedPathLookup;
import io.uhndata.iap.deletion.internal.DeletionServiceImpl;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.utils.DateUtils;

/**
 * A HTL Use-API for the 404 page: it answers "was something that used to live here deleted?" about the path the
 * request was for, so that a dead link can say the resource was deleted rather than that it never existed. To use
 * it, place the following in the error handler HTL file:
 *
 * <p>
 * <code>
 * &lt;sly data-sly-use.deletion="io.uhndata.iap.deletion.scripting.DeletionMetadata"&gt;&lt;/sly&gt;
 * </code>
 * </p>
 *
 * <p>
 * The page renders the answer into the markup it was already sending, so a reader who followed a dead link is told
 * what became of it in the first response rather than after a second round trip. Every getter here is
 * {@code null} when there is nothing to say, which is what HTL needs to leave the attribute carrying it out of the
 * markup altogether.
 * </p>
 *
 * <p>
 * What it discloses is deliberately narrow. <b>Any authenticated reader</b> is told that the path was deleted and
 * when. Anonymous readers never arrive here — the platform requires authentication for everything outside a small
 * allowlist, so a logged-out request for content is redirected to the login page and never reaches a 404 — which
 * is what keeps this from announcing to the world that a path once existed. What is disclosed to a reader who
 * could not have read the resource is a date, and the fact that a path they had to know in advance was once in use.
 * </p>
 *
 * <p>
 * <b>A reader who can read the archive entry</b> — administrators — additionally learns who deleted it and
 * where to look at it. The test is a plain read through the requester's own session, so it is the repository's
 * answer rather than a second, parallel notion of who may see the archive. Nothing here offers to restore
 * anything: the entry's own page already states what a restore or a purge would do before either is attempted,
 * and that is where the decision belongs.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DeletionMetadata implements Use
{
    /**
     * Where the archive browser shows one entry. The console route and the repository path parted company on
     * purpose, so this is not derivable from {@code DeletionService.ARCHIVE_PATH}; it is the {@code ext:targetURL}
     * of {@code Extensions/Admin/Views/ArchiveEntry.json}, and has to move with it.
     */
    static final String ENTRY_ROUTE = "/admin/archive/";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeletionMetadata.class);

    private String deletedAt;

    private String deletedBy;

    private String entryUrl;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        final SlingJakartaHttpServletRequest request = (SlingJakartaHttpServletRequest) bindings.get("jakartaRequest");
        final SlingScriptHelper sling = (SlingScriptHelper) bindings.get(SlingBindings.SLING);
        final String path = requestedPath(request);
        if (path == null) {
            return;
        }
        final ResourceResolverFactory factory = sling.getService(ResourceResolverFactory.class);
        if (factory == null) {
            // An error page is the worst place to raise an error of its own, so a platform missing the service
            // every other part of it depends on still gets a plain, working 404
            LOGGER.warn("Cannot look up whether {} was deleted: there is no resource resolver factory", path);
            return;
        }
        try (ResourceResolver serviceResolver = factory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, DeletionServiceImpl.SUBSERVICE))) {
            final Session serviceSession = serviceResolver.adaptTo(Session.class);
            if (serviceSession == null) {
                throw new RepositoryException("The deletion service resolver is not backed by a repository session");
            }
            DeletedPathLookup.find(serviceSession, path).ifPresent(archived -> describe(archived, request));
        } catch (final LoginException | RepositoryException e) {
            // The page falls back to a plain "does not exist", so this failure is invisible to the reader — an
            // administrator only finds out if it is recorded here
            LOGGER.warn("Failed to look up whether {} was deleted: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(DeletionMetadata.class, "deletedPathLookup").about(path));
        }
    }

    /**
     * When the requested path was deleted, and the signal that it was deleted at all.
     *
     * @return an ISO-8601 timestamp, or {@code null} if nothing archived covers the requested path
     */
    @Nullable
    public String getDeletedAt()
    {
        return this.deletedAt;
    }

    /**
     * Who deleted it, for a reader allowed to know.
     *
     * @return the user who requested the deletion, or {@code null} if the reader cannot read the archive entry
     */
    @Nullable
    public String getDeletedBy()
    {
        return this.deletedBy;
    }

    /**
     * Where to look at the archive entry, for a reader allowed to open it.
     *
     * @return the console route to the entry, or {@code null} if the reader cannot read it
     */
    @Nullable
    public String getEntryUrl()
    {
        return this.entryUrl;
    }

    /**
     * The repository path this request was for.
     *
     * <p>
     * An error handler runs on the request that failed, so the path is the one the error dispatch recorded rather
     * than anything the page has to be told: no query parameter carries it, and so nothing has to encode it to get
     * it here. What arrives is a request URI — percent-encoded, and still carrying whatever selectors and
     * extension the reader's link had on it, which is what {@code DeletedPathLookup} peels apart.
     * </p>
     *
     * <p>
     * Undoing the encoding is {@link URI#getPath()}'s job, not {@code URLDecoder}'s: that one is the form decoder,
     * and it reads a literal {@code +} in a path as a space — measured, {@code /a+b} comes back as {@code /a b}. A
     * path a browser produced never escapes {@code +}, so the two disagree on exactly the character a reader
     * cannot escape for us.
     * </p>
     *
     * @param request the request the error handler is running on
     * @return the absolute repository path, or {@code null} if the dispatch recorded no usable one
     */
    @Nullable
    private static String requestedPath(final SlingJakartaHttpServletRequest request)
    {
        final Object recorded = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Without an error dispatch — the script rendered directly — the request's own URI is the one that failed
        final String uri = recorded instanceof String ? (String) recorded : request.getRequestURI();
        if (uri == null || !uri.startsWith("/")) {
            return null;
        }
        try {
            return new URI(uri).getPath();
        } catch (final URISyntaxException e) {
            // Not a path anything could ever have been archived at, so there is nothing to look up and nothing
            // worth recording
            return null;
        }
    }

    /**
     * Record what a reader is allowed to learn about one deletion. Words are left to the page — this says what
     * happened, not how to phrase it.
     *
     * @param archived the deletion that took the requested path away
     * @param request the request the error handler is running on, whose own session decides what may be disclosed
     */
    private void describe(final DeletedPathLookup.Archived archived, final SlingJakartaHttpServletRequest request)
    {
        this.deletedAt = DateUtils.toString(archived.deletedAt());
        if (request.getResourceResolver().getResource(archived.entryPath()) != null) {
            this.deletedBy = archived.deletedBy();
            this.entryUrl = ENTRY_ROUTE + archived.entryName();
        }
    }
}
