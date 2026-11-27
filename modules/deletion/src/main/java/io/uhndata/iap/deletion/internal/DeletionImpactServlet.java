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

import javax.jcr.Node;

import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionService;

/**
 * Says what deleting a content resource would do, on {@code GET <path>.deletion.json}: what goes with it, what refers
 * to it, and which guard refuses it. Nothing changes. The deletion itself is a {@code delete} workflow event. It moves
 * the resource to the archive and refuses while anything refers to it, which is what this describes.
 *
 * <p>
 * It answers 200 in JSON, with {@code executable} false when references or a guard would block the deletion. The
 * requesting user's permissions are not part of the answer. A resource with no JCR node gets 404, one that cannot be
 * deleted at all gets 400, and a failure gets 500.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { Content.RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "deletion" }, extensions = { "json" })
public class DeletionImpactServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private final transient DeletionService deletionService;

    /**
     * Binds the deletion service it asks.
     *
     * @param deletionService works out what a deletion would do
     */
    @Activate
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "An OSGi service, shared by design; SpotBugs takes it for mutable state from its method names")
    public DeletionImpactServlet(@Reference final DeletionService deletionService)
    {
        this.deletionService = deletionService;
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        if (request.getResource().adaptTo(Node.class) == null) {
            DeletionResponses.send(response, HttpServletResponse.SC_NOT_FOUND, "missing",
                "Not a repository resource");
            return;
        }
        try {
            final DeletionImpact impact =
                this.deletionService.analyze(request.getResource(), DeletionOptions.recoverable());
            final JsonObjectBuilder body = DeletionResponses.body(HttpServletResponse.SC_OK, "dryRun");
            body.add("executable", impact.isExecutable());
            DeletionResponses.describeImpact(body, impact);
            DeletionResponses.send(response, HttpServletResponse.SC_OK, body);
        } catch (final IllegalArgumentException e) {
            DeletionResponses.send(response, HttpServletResponse.SC_BAD_REQUEST, "invalid", e.getMessage());
        } catch (final DeletionException e) {
            DeletionResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed", e.getMessage());
        }
    }
}
