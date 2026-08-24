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
package io.uhndata.iap.deletion.internal;

import java.util.Map;
import java.util.Optional;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.scripting.DeletedPathDisclosure;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.utils.DateUtils;

/**
 * Looks a dead link up in the archive on behalf of the 404 page, and decides what its reader may be told.
 *
 * <p>
 * What it discloses is deliberately narrow. <b>Any authenticated reader</b> is told that the path was deleted and
 * when. Anonymous readers never arrive at a 404 — the platform requires authentication for everything outside a
 * small allowlist, so a logged-out request for content is redirected to the login page — which is what keeps this
 * from announcing to the world that a path once existed. What is disclosed to a reader who could not have read the
 * resource is a date, and the fact that a path they had to know in advance was once in use.
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
@Component(service = { DeletedPathDisclosure.class })
public class DeletedPathDisclosureImpl implements DeletedPathDisclosure
{
    /**
     * Where the archive browser shows one entry. The console route and the repository path parted company on
     * purpose, so this is not derivable from {@link DeletionService#ARCHIVE_PATH}; it is the {@code ext:targetURL}
     * of {@code Extensions/Admin/Views/ArchiveEntry.json}, and has to move with it.
     */
    static final String ENTRY_ROUTE = "/admin/archive/";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeletedPathDisclosureImpl.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    @Nullable
    public Disclosure describe(@NotNull final SlingJakartaHttpServletRequest request,
        @NotNull final String requestedPath)
    {
        try (ResourceResolver serviceResolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, DeletionServiceImpl.SUBSERVICE))) {
            final Session serviceSession = serviceResolver.adaptTo(Session.class);
            if (serviceSession == null) {
                throw new RepositoryException("The deletion service resolver is not backed by a repository session");
            }
            if (!serviceSession.nodeExists(DeletionService.ARCHIVE_PATH)) {
                // repoinit creates the archive, so it is always there: not seeing it means this session is not the
                // one this component asked for. Saying so is the whole point — the query would otherwise come back
                // empty and every dead link would report that it had never been a link at all
                LOGGER.warn("Cannot tell whether {} was deleted: {} is not readable by the deletion service session",
                    requestedPath, DeletionService.ARCHIVE_PATH);
                return null;
            }
            final Optional<DeletedPathLookup.Archived> found =
                DeletedPathLookup.find(serviceSession, requestedPath);
            return found.map(archived -> this.disclose(archived, request)).orElse(null);
        } catch (final LoginException | RepositoryException e) {
            // The page falls back to a plain "does not exist", so this failure is invisible to the reader — an
            // administrator only finds out if it is recorded here
            LOGGER.warn("Failed to look up whether {} was deleted: {}", requestedPath, e.getMessage(), e);
            ErrorLogger.logError(e,
                ErrorContext.of(DeletedPathDisclosureImpl.class, "deletedPathLookup").about(requestedPath));
            return null;
        }
    }

    /**
     * Narrow one deletion down to what this reader may know about it.
     *
     * @param archived the deletion that took the requested path away
     * @param request the request that 404ed, whose own session decides what may be disclosed
     * @return the facts to hand to the page, or {@code null} if there is no date to state them against
     */
    @Nullable
    Disclosure disclose(final DeletedPathLookup.Archived archived, final SlingJakartaHttpServletRequest request)
    {
        final String deletedAt = DateUtils.toString(archived.deletedAt());
        if (deletedAt == null) {
            // The date is what tells the page it was deleted at all, so a deletion without one cannot be reported
            // as a deletion. An archive entry always carries its jcr:created, so this is the repository being
            // broken rather than a link being dead, and it is worth saying so out loud.
            LOGGER.warn("Not reporting that {} was deleted: the archive entry has no usable creation date",
                archived.entryPath());
            return null;
        }
        if (request.getResourceResolver().getResource(archived.entryPath()) == null) {
            return new Disclosure(deletedAt, null, null);
        }
        return new Disclosure(deletedAt, archived.deletedBy(), ENTRY_ROUTE + archived.entryName());
    }
}
