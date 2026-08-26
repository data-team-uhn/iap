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
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowHomepagesServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowHomepagesServletTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String CHILD_NODE_TYPE = "childNodeType";

    private static final String WORKFLOW_DEFINITION = "wf:WorkflowDefinition";

    private static final String WORKFLOWS = "/Workflows";

    private static final String SYSTEM_WORKFLOWS = "/SystemWorkflows";

    private final SlingContext context = new SlingContext();

    private WorkflowHomepagesServlet servlet;

    /** The resolver the request carries, with the endpoint's query stubbed on it. */
    private ResourceResolver resolver;

    @BeforeEach
    void setUp()
    {
        this.servlet = new WorkflowHomepagesServlet();
        this.resolver = this.context.resourceResolver();
    }

    @Test
    void listsEveryHomepageHoldingWorkflowsWithTheQueriedOneFirst() throws IOException
    {
        // Deliberately created in an order that is neither the answer's nor alphabetical, so the ordering the
        // endpoint imposes is the one being read
        this.createHomepage(SYSTEM_WORKFLOWS, "wf/SystemWorkflowsHomepage", WORKFLOW_DEFINITION, null);
        this.createHomepage("/Archive/Workflows", "wf/WorkflowsHomepage", WORKFLOW_DEFINITION, "Archived workflows");
        this.createHomepage(WORKFLOWS, "wf/WorkflowsHomepage", WORKFLOW_DEFINITION, "Workflows");
        this.stubQuery(SYSTEM_WORKFLOWS, "/Archive/Workflows", WORKFLOWS);

        final JsonArray homepages = this.get(WORKFLOWS).getJsonArray("homepages");

        assertEquals(3, homepages.size());
        // The queried homepage leads, the others follow by path
        assertEquals(WORKFLOWS, homepages.getJsonObject(0).getString("path"));
        assertEquals("/Archive/Workflows", homepages.getJsonObject(1).getString("path"));
        assertEquals(SYSTEM_WORKFLOWS, homepages.getJsonObject(2).getString("path"));
        assertEquals("Archived workflows", homepages.getJsonObject(1).getString("title"));
    }

    @Test
    void leavesOutHomepagesThatHoldSomethingElse() throws IOException
    {
        this.createHomepage(WORKFLOWS, "wf/WorkflowsHomepage", WORKFLOW_DEFINITION, null);
        this.createHomepage("/Submissions", "sub/SubmissionsHomepage", "sub:Submission", null);
        // A homepage naming no child type at all leaves it to the naming convention, and is not a workflow one
        this.createHomepage("/WorkflowTypes", "wf/WorkflowTypesHomepage", null, null);
        this.stubQuery(WORKFLOWS, "/Submissions", "/WorkflowTypes");

        final JsonArray homepages = this.get(WORKFLOWS).getJsonArray("homepages");

        assertEquals(1, homepages.size());
        assertEquals(WORKFLOWS, homepages.getJsonObject(0).getString("path"));
    }

    @Test
    void namesAHomepageByItsNodeNameWhenItHasNoTitle() throws IOException
    {
        this.createHomepage(WORKFLOWS, "wf/WorkflowsHomepage", WORKFLOW_DEFINITION, null);
        this.stubQuery(WORKFLOWS);

        final JsonArray homepages = this.get(WORKFLOWS).getJsonArray("homepages");

        assertEquals("Workflows", homepages.getJsonObject(0).getString("title"));
    }

    @Test
    void answersWithAnEmptyListWhenTheCallerCanSeeNone() throws IOException
    {
        // A caller who may read no workflow homepage at all: the query finds nothing, which is an empty list rather
        // than an error — the client falls back to listing nothing rather than to guessing at paths
        this.createHomepage(WORKFLOWS, "wf/WorkflowsHomepage", WORKFLOW_DEFINITION, null);
        this.stubQuery();

        assertTrue(this.get(WORKFLOWS).getJsonArray("homepages").isEmpty());
    }

    /**
     * Creates a homepage node.
     *
     * @param path where to create it
     * @param resourceType its resource type
     * @param childNodeType the entity type it holds, or {@code null} to leave it unset
     * @param title its title, or {@code null} to leave it unset
     */
    private void createHomepage(final String path, final String resourceType, final String childNodeType,
        final String title)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, resourceType);
        if (childNodeType != null) {
            properties.put(CHILD_NODE_TYPE, childNodeType);
        }
        if (title != null) {
            properties.put("title", title);
        }
        this.context.create().resource(path, properties);
    }

    /**
     * Teaches the resolver what the endpoint's query finds. The mock repository has no query engine, so the results
     * stand in for one — which is also how "a homepage the caller cannot read" is expressed here: it exists, and the
     * query, running on that caller's session, does not return it.
     *
     * @param paths the paths the query answers with, in the order it answers them
     */
    private void stubQuery(final String... paths)
    {
        final List<Resource> found = List.of(paths).stream()
            .map(path -> {
                final Resource resource = this.context.resourceResolver().getResource(path);
                assertNotNull(resource);
                return resource;
            })
            .toList();
        this.resolver = Mockito.spy(this.context.resourceResolver());
        Mockito.doAnswer(invocation -> found.iterator())
            .when(this.resolver).findResources(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * Runs the endpoint against a homepage.
     *
     * @param path the homepage to query
     * @return the parsed response body
     * @throws IOException if the request fails
     */
    private JsonObject get(final String path) throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.resolver, this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(path));
        ((MockRequestPathInfo) request.getRequestPathInfo()).setSelectorString("homepages");
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request, response);

        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }
}
