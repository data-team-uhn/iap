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

import java.util.Calendar;
import java.util.Map;

import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WorkflowToken}, in particular resolving where a token rests back onto the definition.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowTokenTest
{
    private static final String INSTANCE_PATH = "/WorkflowInstances/i1";

    private static final String VERSION_ID = "workflow-version-uuid";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void exposesWhereItRests()
    {
        final Calendar created = Calendar.getInstance();
        created.set(2026, Calendar.JULY, 20, 9, 0, 0);
        this.context.create().resource(INSTANCE_PATH, TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active");
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1", "jcr:created", created));
        final WorkflowToken token = resource.adaptTo(WorkflowToken.class);

        assertNotNull(token);
        assertEquals("task_1", token.getCurrentNodeId());
        // The creation date is the inherited jcr:created rather than a property of its own
        assertEquals(created, token.getCreated());
    }

    @Test
    void findsTheInstanceItBelongsTo()
    {
        this.context.create().resource(INSTANCE_PATH, TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active");
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));

        assertEquals("active", resource.adaptTo(WorkflowToken.class).getWorkflowInstance().getStatus());
    }

    @Test
    void hasNoInstanceWhenStoredOutsideOne()
    {
        final Resource resource = this.context.create().resource("/loose/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));
        final WorkflowToken token = resource.adaptTo(WorkflowToken.class);

        assertNull(token.getWorkflowInstance());
        assertNull(token.getCurrentNode());
    }

    @Test
    void resolvesTheNodeItRestsOn()
        throws RepositoryException
    {
        this.createDefinition();
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));

        final FlowNode node = resource.adaptTo(WorkflowToken.class).getCurrentNode();

        assertNotNull(node);
        assertEquals(Activity.class, node.getClass());
        assertEquals("Approve the request", node.getLabel());
    }

    @Test
    void hasNoCurrentNodeWhenItRestsOnAnUnknownOne()
        throws RepositoryException
    {
        this.createDefinition();
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "no_such_node"));

        assertNull(resource.adaptTo(WorkflowToken.class).getCurrentNode());
    }

    @Test
    void hasNoCurrentNodeWhenItNamesNoNodeAtAll()
        throws RepositoryException
    {
        // currentNodeId is mandatory in the node type; a token that got past that rests nowhere, which the lookup
        // must report as no node rather than throw over
        this.createDefinition();
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE));

        assertNull(resource.adaptTo(WorkflowToken.class).getCurrentNode());
    }

    @Test
    void hasNoCurrentNodeWhenTheVersionCannotBeResolved()
    {
        this.context.create().resource(INSTANCE_PATH, TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active");
        final Resource resource = this.context.create().resource(INSTANCE_PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));

        assertNotNull(resource.adaptTo(WorkflowToken.class).getWorkflowInstance());
        assertNull(resource.adaptTo(WorkflowToken.class).getCurrentNode());
    }

    /**
     * Creates a one-activity workflow version and an instance executing it.
     *
     * @throws RepositoryException never, only declared by the mocked JCR API
     */
    private void createDefinition()
        throws RepositoryException
    {
        this.context.create().resource("/Workflows/timeOff/1.0", TYPE, WorkflowVersion.RESOURCE_TYPE,
            "version", "1.0");
        this.context.create().resource("/Workflows/timeOff/1.0/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1", "label", "Approve the request"));
        WorkflowFixture.resolveReference(this.context, VERSION_ID, "/Workflows/timeOff/1.0");
        this.context.create().resource(INSTANCE_PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active", "workflowVersion", VERSION_ID));
    }
}
