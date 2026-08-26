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
package io.uhndata.iap.workflows.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.jcr.query.Query;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.models.WorkflowsHomepage;

/**
 * Lists the homepages workflow definitions are stored in: {@code GET /Workflows.homepages.json} answers with every
 * entity homepage the caller can read that holds {@code wf:WorkflowDefinition} entities, the queried one first.
 *
 * <p>
 * This is what lets a listing show the workflows of more than one tree — the ordinary ones and, for whoever may read
 * them, the platform's own under {@code /SystemWorkflows} — without either end hardcoding which trees exist. A
 * deployment that adds another (a second location's workflows, mirrored locally) is picked up by having created it,
 * with nothing to configure; the paths this returns are the ones a client hands back as the pagination servlet's
 * {@code scope} parameters.
 * </p>
 *
 * <p>
 * The query runs on the caller's own session, so a homepage they cannot read is simply not in the answer: the list
 * describes what this user may list, never what exists.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { WorkflowsHomepage.RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "homepages" }, extensions = { "json" })
public class WorkflowHomepagesServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    /**
     * Every entity homepage, of any concrete type. {@code data:EntityHomepage} is declared queryable exactly so this
     * kind of question can be asked, and a node type query is served by Oak's own type index, needing none of its
     * own. Which of them hold workflows is decided afterwards, on a handful of nodes, rather than by adding an index
     * for a property only this endpoint reads.
     */
    private static final String HOMEPAGE_QUERY = "select * from [data:EntityHomepage]";

    /** The node type whose homepages this endpoint is about. */
    private static final String WORKFLOW_DEFINITION_TYPE = "wf:WorkflowDefinition";

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final String queried = request.getResource().getPath();
        final List<Resource> homepages = new ArrayList<>();
        final Iterator<Resource> found =
            request.getResourceResolver().findResources(HOMEPAGE_QUERY, Query.JCR_SQL2);
        while (found.hasNext()) {
            final Resource homepage = found.next();
            if (WORKFLOW_DEFINITION_TYPE.equals(homepage.getValueMap().get("childNodeType", String.class))) {
                homepages.add(homepage);
            }
        }
        // The homepage that was asked leads the list, since it is the one the caller already knows about and the one
        // a listing is anchored on; the rest are ordered by path, so the answer does not depend on the query's own
        homepages.sort(Comparator.comparing((final Resource homepage) -> !queried.equals(homepage.getPath()))
            .thenComparing(Resource::getPath));

        final JsonArrayBuilder listed = Json.createArrayBuilder();
        homepages.forEach(homepage -> listed.add(describe(homepage)));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(Json.createObjectBuilder().add("homepages", listed).build().toString());
    }

    /**
     * One homepage, as much of it as a client listing workflows from several needs: where it is, and what to call it.
     *
     * @param homepage the homepage to describe
     * @return a JSON object builder holding its path and title
     */
    private static JsonObjectBuilder describe(final Resource homepage)
    {
        final ValueMap properties = homepage.getValueMap();
        return Json.createObjectBuilder()
            .add("path", homepage.getPath())
            .add("title", properties.get("title", homepage.getName()));
    }
}
