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
import java.util.stream.Collectors;

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
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;

/**
 * Purges an archive entry: {@code DELETE /Archive/<entry>} permanently removes the entry and everything archived
 * in it. Guards are consulted again, so archived resources protected from deletion block the purge with a 409.
 * Only users who can see the archive — in practice, administrators — can reach this endpoint; everyone else gets
 * a 404 from the resource resolution itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { RestoreServlet.ENTRY_RESOURCE_TYPE }, methods = { "DELETE" })
public class PurgeServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 1L;

    @Reference
    private transient DeletionService deletionService;

    @Override
    protected void doDelete(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try {
            final DeletionResult result = this.deletionService.purge(request.getResource());
            if (result.getStatus() == DeletionResult.Status.DELETED) {
                JsonResponses.send(response, HttpServletResponse.SC_OK, "deleted", "");
                return;
            }
            final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_CONFLICT, "vetoed");
            body.add("status.message", result.getImpact().getVetoes().stream()
                .map(Veto::getReason)
                .distinct()
                .collect(Collectors.joining("; ")));
            body.add("vetoes", JsonResponses.vetoes(result.getImpact().getVetoes()));
            JsonResponses.send(response, HttpServletResponse.SC_CONFLICT, body);
        } catch (final IllegalArgumentException e) {
            JsonResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed", e.getMessage());
        }
    }
}
