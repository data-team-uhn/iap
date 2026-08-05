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
 * Unit tests for {@link WorkflowInstances}, the container the {@code wf:WorkflowAttachable} mixin autocreates
 * inside whatever a workflow drives.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowInstancesTest
{
    private static final String HOST_PATH = "/Submissions/submission";

    private static final String CONTAINER_PATH = HOST_PATH + "/" + WorkflowInstances.NODE_NAME;

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(HOST_PATH, TYPE, "sub/Submission");
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(CONTAINER_PATH,
            TYPE, WorkflowInstances.RESOURCE_TYPE);

        assertNotNull(resource.adaptTo(WorkflowInstances.class));
    }

    @Test
    void listsTheWorkflowsRunningOverTheHost()
    {
        final Resource resource = this.context.create().resource(CONTAINER_PATH,
            TYPE, WorkflowInstances.RESOURCE_TYPE);
        this.context.create().resource(CONTAINER_PATH + "/review", Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "in-review"));
        this.context.create().resource(CONTAINER_PATH + "/reminders", Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));
        // Anything else stored in here is not a workflow and must not be listed as one
        this.context.create().resource(CONTAINER_PATH + "/stray", TYPE, "nt:unstructured");

        final List<WorkflowInstance> instances = resource.adaptTo(WorkflowInstances.class).getInstances();

        assertEquals(2, instances.size());
        assertEquals("in-review", instances.get(0).getStatus());
        assertEquals("active", instances.get(1).getStatus());
    }

    @Test
    void listsNothingBeforeAnyWorkflowHasStarted()
    {
        // The container is autocreated, so an empty one is the normal state of a resource nothing has run over
        final Resource resource = this.context.create().resource(CONTAINER_PATH,
            TYPE, WorkflowInstances.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(WorkflowInstances.class).getInstances().isEmpty());
    }
}
