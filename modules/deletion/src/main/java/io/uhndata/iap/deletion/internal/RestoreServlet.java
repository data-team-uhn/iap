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

import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreResult;

/**
 * Restores an archive entry: {@code POST /Archive/<entry>.restore.json} moves every archived item back to its
 * recorded original location and removes the emptied entry. The restore is all-or-nothing; when anything is in the
 * way, a 409 response lists every conflict and nothing is changed. Only users who can see the archive — in
 * practice, administrators — can reach this endpoint; everyone else gets a 404 from the resource resolution
 * itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { RestoreServlet.ENTRY_RESOURCE_TYPE }, methods = { "POST" },
    selectors = { "restore" }, extensions = { "json" })
public class RestoreServlet extends SlingJakartaAllMethodsServlet
{
    /** The {@code sling:resourceType} of an {@code iap:ArchiveEntry} node. */
    static final String ENTRY_RESOURCE_TYPE = "iap/ArchiveEntry";

    private static final long serialVersionUID = 1L;

    @Reference
    private transient DeletionService deletionService;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try {
            final RestoreResult result = this.deletionService.restore(request.getResource());
            if (result.getStatus() == RestoreResult.Status.RESTORED) {
                final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_OK, "restored");
                body.add("restored", JsonResponses.paths(result.getRestoredPaths()));
                JsonResponses.send(response, HttpServletResponse.SC_OK, body);
                return;
            }
            this.sendConflicts(response, result);
        } catch (final IllegalArgumentException e) {
            JsonResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed", e.getMessage());
        }
    }

    private void sendConflicts(final SlingJakartaHttpServletResponse response, final RestoreResult result)
        throws IOException
    {
        final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_CONFLICT, "conflict");
        body.add("status.message", "Some archived items cannot be restored; nothing was changed");
        body.add("conflicts", JsonResponses.conflicts(result.getConflicts()));
        JsonResponses.send(response, HttpServletResponse.SC_CONFLICT, body);
    }
}
