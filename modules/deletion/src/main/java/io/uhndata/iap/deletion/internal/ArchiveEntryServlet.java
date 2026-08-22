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
 * Describes one archive entry: {@code GET /Archive/<xx>/<yy>/<zz>/<entry>.entry.json} answers with what was
 * archived and, crucially, whether the two things that can be done about it would actually work.
 *
 * <p>
 * Both actions can fail for reasons that are knowable in advance — a restore whose original parent is gone or whose
 * path has been taken, a purge a guard refuses — and until now the only way to find out was to attempt one and read
 * the 409. This endpoint runs the same evaluations the operations run before they change anything, so a page can
 * state the consequences before anybody commits to them. That is the shape the deletion endpoint already uses for
 * its own {@code dryRun}.
 * </p>
 *
 * <p>
 * A preflight is a snapshot, not a promise: another deletion can occupy a path, and a retention floor expires. The
 * operations therefore evaluate again rather than trusting this, and a client should treat a refusal arriving after
 * a clean preflight as ordinary rather than as an error.
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
                .add("restoreConflicts", JsonResponses.conflicts(conflicts))
                .add("purgeable", vetoes.isEmpty())
                .add("purgeVetoes", JsonResponses.vetoes(vetoes));
            JsonResponses.send(response, HttpServletResponse.SC_OK, body);
        } catch (final IllegalArgumentException e) {
            JsonResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            LOGGER.warn("Failed to describe the archive entry {}: {}", request.getResource().getPath(),
                e.getMessage(), e);
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed",
                "The archive entry cannot be examined");
        }
    }
}
