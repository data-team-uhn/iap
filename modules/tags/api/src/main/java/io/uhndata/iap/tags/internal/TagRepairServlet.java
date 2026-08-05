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
package io.uhndata.iap.tags.internal;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.api.TagRepairService;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;
import io.uhndata.iap.tags.models.TagsHomepage;

/**
 * Repairs the content affected by an edited tag definition, on request. Served at {@code POST /Tags.repair.json},
 * with the name of the tag whose definition changed as the {@code tag} parameter.
 *
 * <p>
 * This is the deliberate half of tag repair. Deleting a definition, or changing whether a tag is inheritable or
 * aggregated, invalidates copies of it anywhere in the repository without touching any of the content carrying them,
 * so nothing recomputes them on its own. How much work that is depends on how widely the tag is used, which is why
 * it is asked for rather than done automatically when a definition is saved.
 * </p>
 *
 * <p>
 * Whoever may edit the definitions may repair after editing them: the permission is read off {@code /Tags} through
 * the caller's own session rather than compared against a user name, so it follows whatever access control the
 * deployment actually has.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { TagsHomepage.RESOURCE_TYPE }, methods = { "POST" },
    selectors = { "repair" }, extensions = { "json" })
public class TagRepairServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 3299845124403109461L;

    @Reference
    private transient TagRepairService repairService;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!mayEditDefinitions(request)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Repairing tags requires write access to "
                + TagManager.DEFINITIONS_PATH);
            return;
        }
        final String tag = request.getParameter("tag");
        if (tag == null || tag.isBlank()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Missing the name of the tag to repair");
            return;
        }

        final RepairReport report = this.repairService.repair(tag);
        response.getWriter().write(Json.createObjectBuilder()
            .add("status", report.isComplete() ? "ok" : "incomplete")
            .add("tag", tag)
            .add("marked", report.marked())
            .add("failed", report.failed())
            .build().toString());
    }

    /**
     * Whether the caller may edit the tag definitions, which is the permission repairing after an edit follows.
     * Asking the repository for a writable view is how that is decided: it answers for whatever access control is
     * configured, where comparing the user name against {@code admin} would answer only for one deployment.
     *
     * @param request the request, carrying the caller's own session
     * @return {@code true} if the caller may write to the definitions
     */
    private boolean mayEditDefinitions(final SlingJakartaHttpServletRequest request)
    {
        final Resource definitions = request.getResourceResolver().getResource(TagManager.DEFINITIONS_PATH);
        return definitions != null && definitions.adaptTo(ModifiableValueMap.class) != null;
    }

    private void error(final SlingJakartaHttpServletResponse response, final int status, final String message)
        throws IOException
    {
        response.setStatus(status);
        response.getWriter().write(Json.createObjectBuilder()
            .add("status", "error")
            .add("error", message)
            .build().toString());
    }
}
