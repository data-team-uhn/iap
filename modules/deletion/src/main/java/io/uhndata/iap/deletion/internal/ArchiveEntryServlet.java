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

import java.io.IOException;
import java.util.List;

import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.Veto;

/**
 * Describes one archive entry: {@code GET <entry>.entry.json} answers with what the entry holds, and whether a
 * restore and a purge would succeed now. It runs the checks that {@link DeletionService#restore} and
 * {@link DeletionService#purge} make before they change anything. Each item that cannot go back is listed with its
 * conflict, and each guard that refuses the purge with its reason.
 *
 * <p>
 * The answer holds only until something changes. Another deletion can occupy a path, and an archived resource can
 * gain the {@code del:Undeletable} mixin. A restore or purge refused after a clean answer is an ordinary outcome, not
 * an error.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { RestoreServlet.ENTRY_RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "entry" }, extensions = { "json" })
public class ArchiveEntryServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveEntryServlet.class);

    @Reference
    private transient DeletionService deletionService;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try {
            final List<RestoreConflict> conflicts = this.deletionService.checkRestore(request.getResource());
            final List<Veto> vetoes = this.deletionService.checkPurge(request.getResource());
            final JsonObjectBuilder body = ArchiveEntryRow.of(request.getResource())
                .add("restorable", conflicts.isEmpty())
                .add("restoreConflicts", DeletionResponses.conflicts(conflicts))
                .add("purgeable", vetoes.isEmpty())
                .add("purgeVetoes", DeletionResponses.vetoes(vetoes));
            DeletionResponses.sendContent(response, HttpServletResponse.SC_OK, body);
        } catch (final IllegalArgumentException e) {
            DeletionResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            LOGGER.warn("Failed to describe the archive entry {}: {}", request.getResource().getPath(),
                e.getMessage(), e);
            DeletionResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed",
                "The archive entry cannot be examined");
        }
    }
}
