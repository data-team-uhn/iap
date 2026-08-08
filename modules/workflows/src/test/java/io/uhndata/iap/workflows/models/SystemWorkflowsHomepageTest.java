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
package io.uhndata.iap.workflows.models;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SystemWorkflowsHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SystemWorkflowsHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(SystemWorkflowsHomepage.PATH,
            TYPE, SystemWorkflowsHomepage.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(SystemWorkflowsHomepage.class));
    }

    @Test
    void listsTheSystemWorkflows()
    {
        final Resource resource = this.context.create().resource(SystemWorkflowsHomepage.PATH,
            TYPE, SystemWorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(SystemWorkflowsHomepage.PATH + "/createWorkflow", Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Create a workflow"));
        // Something that is not a workflow must not be listed as one
        this.context.create().resource(SystemWorkflowsHomepage.PATH + "/stray", TYPE, "nt:unstructured");

        final List<WorkflowDefinition> workflows =
            resource.adaptTo(SystemWorkflowsHomepage.class).getWorkflows();

        assertEquals(1, workflows.size());
        assertEquals("Create a workflow", workflows.get(0).getTitle());
    }

    @Test
    void listsNoWorkflowsWhenNoneExist()
    {
        final Resource resource = this.context.create().resource(SystemWorkflowsHomepage.PATH,
            TYPE, SystemWorkflowsHomepage.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(SystemWorkflowsHomepage.class).getWorkflows().isEmpty());
    }
}
