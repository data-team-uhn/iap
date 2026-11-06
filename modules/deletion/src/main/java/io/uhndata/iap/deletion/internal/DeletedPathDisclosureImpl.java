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
 * Looks a missing path up in the archive, through the {@code iap-deletion} service user's session, and decides what
 * the reader may be told about it.
 *
 * <p>
 * A reader who can read the archive entry learns when the path was deleted, who deleted it, and where the entry is.
 * The user who deleted it, if they cannot read the entry, learns only when. Anybody else is told nothing. Whether the
 * reader can read the entry is a plain read through their own resolver.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { DeletedPathDisclosure.class })
public class DeletedPathDisclosureImpl implements DeletedPathDisclosure
{
    /**
     * The console route of the page showing one archive entry, followed by the entry's name. It is the
     * {@code ext:targetURL} of {@code Extensions/Admin/Views/ArchiveEntry.json}, and has to change with it.
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
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE,
                DeletionServiceImpl.SUBSERVICE_NAME))) {
            final Session serviceSession = serviceResolver.adaptTo(Session.class);
            if (serviceSession == null) {
                throw new RepositoryException("The deletion service resolver is not backed by a repository session");
            }
            if (!serviceSession.nodeExists(DeletionService.ARCHIVE_PATH)) {
                // repoinit creates the archive, so this session is not the service user's. Without this warning
                // the query finds nothing, and every deleted path reads as one that never existed.
                LOGGER.warn("Cannot tell whether {} was deleted: {} is not readable by the deletion service session",
                    requestedPath, DeletionService.ARCHIVE_PATH);
                return null;
            }
            final Optional<DeletedPathLookup.Archived> found =
                DeletedPathLookup.find(serviceSession, requestedPath);
            return found.map(archived -> this.disclose(archived, request)).orElse(null);
        } catch (final LoginException | RepositoryException e) {
            // The reader sees a plain "not found", so only the error log tells an administrator
            LOGGER.warn("Failed to look up whether {} was deleted: {}", requestedPath, e.getMessage(), e);
            ErrorLogger.logError(e,
                ErrorContext.of(DeletedPathDisclosureImpl.class, "deletedPathLookup").about(requestedPath));
            return null;
        }
    }

    /**
     * What this reader may know about one deletion.
     *
     * @param archived the deletion that took the requested path away
     * @param request the request that failed with a 404, whose resolver decides what may be disclosed
     * @return the facts to hand to the page; {@code null} if this reader is to be told nothing
     */
    @Nullable
    Disclosure disclose(final DeletedPathLookup.Archived archived, final SlingJakartaHttpServletRequest request)
    {
        final String deletedAt = DateUtils.toString(archived.deletedAt());
        if (deletedAt == null) {
            // The page reads a deletion from its date, so without one there is nothing to report
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
