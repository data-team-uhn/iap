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

import javax.jcr.Node;

import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;

/**
 * Handles {@code DELETE} requests on any content resource. Without parameters the deletion is refused if resources
 * other than links reference the target, and the affected resources are moved into the archive; the optional
 * boolean parameters adjust this: {@code recursive} also deletes the referencing resources, {@code permanent}
 * skips the archive, and {@code dryRun} only reports what the deletion would do, changing nothing.
 *
 * <p>
 * Responses are JSON: 200 with the outcome, 409 when references or vetoes block the deletion, 401/403 when the
 * requester may not remove everything involved, 404 for a non-repository resource, 500 for unexpected failures.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { Content.RESOURCE_TYPE }, methods = { "DELETE" })
public class DeleteServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 1L;

    @Reference
    private transient DeletionService deletionService;

    @Override
    protected void doDelete(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        if (request.getResource().adaptTo(Node.class) == null) {
            JsonResponses.send(response, HttpServletResponse.SC_NOT_FOUND, "missing",
                "Not a repository resource");
            return;
        }
        final DeletionOptions options = DeletionOptions.of(
            Boolean.parseBoolean(request.getParameter("recursive")),
            Boolean.parseBoolean(request.getParameter("permanent")));
        try {
            if (Boolean.parseBoolean(request.getParameter("dryRun"))) {
                this.sendDryRun(response, this.deletionService.analyze(request.getResource(), options));
                return;
            }
            this.sendOutcome(request, response,
                this.deletionService.delete(request.getResource(), options));
        } catch (final IllegalArgumentException e) {
            JsonResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed", e.getMessage());
        }
    }

    private void sendDryRun(final SlingJakartaHttpServletResponse response, final DeletionImpact impact)
        throws IOException
    {
        final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_OK, "dryRun");
        body.add("executable", impact.isExecutable());
        JsonResponses.describeImpact(body, impact);
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }

    private void sendOutcome(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response, final DeletionResult result) throws IOException
    {
        switch (result.getStatus()) {
            case ARCHIVED -> this.sendSuccess(response, result, "archived");
            case DELETED -> this.sendSuccess(response, result, "deleted");
            case VETOED -> this.sendVetoed(response, result);
            case REQUIRES_CONFIRMATION -> this.sendReferenced(response, result);
            // DENIED
            default -> JsonResponses.send(response,
                request.getRemoteUser() == null ? HttpServletResponse.SC_UNAUTHORIZED
                    : HttpServletResponse.SC_FORBIDDEN,
                "denied", "You are not allowed to delete everything this deletion would impact");
        }
    }

    private void sendSuccess(final SlingJakartaHttpServletResponse response, final DeletionResult result,
        final String status) throws IOException
    {
        final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_OK, status);
        if (result.getArchiveEntryPath() != null) {
            body.add("archiveEntry", result.getArchiveEntryPath());
        }
        body.add("items", JsonResponses.paths(result.getImpact().getItemPaths()));
        body.add("removedLinks", JsonResponses.paths(result.getImpact().getRemovedLinkPaths()));
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }

    private void sendVetoed(final SlingJakartaHttpServletResponse response, final DeletionResult result)
        throws IOException
    {
        final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_CONFLICT, "vetoed");
        body.add("status.message", result.getImpact().getVetoes().stream()
            .map(Veto::getReason)
            .distinct()
            .collect(Collectors.joining("; ")));
        body.add("vetoes", JsonResponses.vetoes(result.getImpact().getVetoes()));
        JsonResponses.send(response, HttpServletResponse.SC_CONFLICT, body);
    }

    private void sendReferenced(final SlingJakartaHttpServletResponse response, final DeletionResult result)
        throws IOException
    {
        final JsonObjectBuilder body = JsonResponses.body(HttpServletResponse.SC_CONFLICT, "referenced");
        JsonResponses.describeImpact(body, result.getImpact());
        JsonResponses.send(response, HttpServletResponse.SC_CONFLICT, body);
    }
}
