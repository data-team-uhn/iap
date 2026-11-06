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
import io.uhndata.iap.utils.JsonResponses;

/**
 * Repairs the content affected by an edited tag definition, on request: {@code POST /Tags.repair.json}, with the
 * name of the tag as the {@code tag} parameter. The answer is {@code ok}, or {@code incomplete} if some nodes could
 * not be marked, with the numbers of nodes marked and not marked.
 *
 * <p>
 * A caller who may set properties on {@code /Tags} may repair, as the caller's own session answers.
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
        if (!mayEditDefinitions(request)) {
            JsonResponses.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                "Repairing tags requires write access to " + TagManager.DEFINITIONS_PATH);
            return;
        }
        final String tag = request.getParameter("tag");
        if (tag == null || tag.isBlank()) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing the name of the tag to repair");
            return;
        }

        final RepairReport report = this.repairService.repair(tag);
        JsonResponses.write(response, HttpServletResponse.SC_OK, report.isComplete() ? "ok" : "incomplete",
            Json.createObjectBuilder().add("tag", tag).add("marked", report.marked()).add("failed", report.failed()));
    }

    /**
     * Whether the caller may set properties on {@code /Tags}. The resolver gives a writable view of it only then.
     *
     * @param request the request, carrying the caller's own session
     * @return {@code true} if the caller may set properties on {@code /Tags}
     */
    private boolean mayEditDefinitions(final SlingJakartaHttpServletRequest request)
    {
        final Resource definitions = request.getResourceResolver().getResource(TagManager.DEFINITIONS_PATH);
        return definitions != null && definitions.adaptTo(ModifiableValueMap.class) != null;
    }
}
