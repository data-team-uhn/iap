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
import io.uhndata.iap.utils.UserIds;

/**
 * Looks a dead link up in the archive on behalf of the 404 page, and decides what its reader may be told.
 *
 * <p>
 * Three answers, by who is asking. A reader who can read the archive entry, which today means an administrator,
 * learns when it went, who deleted it, and where to look at it. The person who deleted it learns when it went, and
 * is offered no link to an archive they cannot open. Anybody else is told nothing and sees an ordinary 404.
 * </p>
 *
 * <p>
 * The archive test is a plain read through the requester's own session. That is the repository's answer rather
 * than a second notion of who may see the archive. The deleter test compares canonical user ids: a login resolves
 * case-insensitively, and the resolver reports the spelling that was typed.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { DeletedPathDisclosure.class })
public class DeletedPathDisclosureImpl implements DeletedPathDisclosure
{
    /**
     * Where the archive browser shows one entry. The console route and the repository path parted company, so
     * this is not derivable from {@link DeletionService#ARCHIVE_PATH}. It is the {@code ext:targetURL} of
     * {@code Extensions/Admin/Views/ArchiveEntry.json}, and has to move with it.
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
                // repoinit creates the archive, so not seeing it means this session is not the one this component
                // asked for. Unreported, the query comes back empty and every dead link claims it was never one
                LOGGER.warn("Cannot tell whether {} was deleted: {} is not readable by the deletion service session",
                    requestedPath, DeletionService.ARCHIVE_PATH);
                return null;
            }
            final Optional<DeletedPathLookup.Archived> found =
                DeletedPathLookup.find(serviceSession, requestedPath);
            return found.map(archived -> this.disclose(archived, request)).orElse(null);
        } catch (final LoginException | RepositoryException e) {
            // The page falls back to a plain "does not exist", so the reader never sees this. Recording it is
            // how an administrator finds out
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
     * @return the facts to hand to the page, or {@code null} when this reader is to be told nothing
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
        if (request.getResourceResolver().getResource(archived.entryPath()) != null) {
            return new Disclosure(deletedAt, archived.deletedBy(), ENTRY_ROUTE + archived.entryName());
        }
        if (archived.deletedBy() != null
            && archived.deletedBy().equals(UserIds.canonical(request.getResourceResolver()))) {
            return new Disclosure(deletedAt, null, null);
        }
        return null;
    }
}
