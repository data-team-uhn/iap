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
package io.uhndata.iap.workflows.testing;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextBuilder;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowPlugins#ENGINE}: the real engine, running a system workflow the test defines in a
 * real repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EnginePluginTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String ELEMENT_ID = "elementId";

    private static final String FLOW_NODE_TYPE = "flowNodeType";

    private static final String TARGET_REF = "targetRef";

    private static final WorkflowEvent NOTE = new WorkflowEvent("note", Map.of("what", "a test"));

    private final SlingContext context = new SlingContextBuilder(ResourceResolverType.JCR_OAK)
        .plugin(WorkflowPlugins.ENGINE)
        .build();

    @Test
    void runsASystemWorkflowTheTestDefines() throws RepositoryException, WorkflowException
    {
        this.context.registerService(ServiceTaskHandler.class, new Noting());
        final Resource target = createNotingWorkflow();

        final WorkflowResult result = engine().receiveEvent(target, NOTE);

        assertEquals("a test", result.getVariable("noted"));
    }

    @Test
    void recordsWhatItCommitsInsteadOfWritingTheHistory() throws RepositoryException, WorkflowException
    {
        this.context.registerService(ServiceTaskHandler.class, new Noting());
        final Resource target = createNotingWorkflow();

        engine().receiveEvent(target, NOTE);

        assertEquals(1, this.context.getService(RecordedDeliveries.class).described().size());
    }

    @Test
    void sendsNoNotificationTheWorkflowDoesNotAnnounce() throws RepositoryException, WorkflowException
    {
        this.context.registerService(ServiceTaskHandler.class, new Noting());
        final Resource target = createNotingWorkflow();

        engine().receiveEvent(target, NOTE);

        assertTrue(this.context.getService(RecordedNotifications.class).sent().isEmpty());
    }

    @Test
    void resolvesTheSpecialNamesThePlatformShips()
    {
        final PrincipalService principals = this.context.getService(PrincipalService.class);

        assertEquals(List.of("someone"),
            principals.resolve(List.of(PrincipalService.ME_SPECIAL_NAME), PrincipalContext.actedBy("someone")));
    }

    private WorkflowEngine engine()
    {
        return this.context.getService(WorkflowEngine.class);
    }

    /**
     * Defines a system workflow for the homepage at {@code /Workflows}: a {@code note} message starts it, a service
     * task performs {@code note}, and it ends.
     *
     * @return the homepage, seen through the test's resolver
     * @throws RepositoryException when the repository refuses the content
     */
    private Resource createNotingWorkflow() throws RepositoryException
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node root = session.getRootNode();
        root.addNode("Workflows", "wf:WorkflowsHomepage");
        final Node type = root.addNode("WorkflowTypes", UNSTRUCTURED).addNode("ServiceTask", "wf:ActivityType");
        type.setProperty("label", "Service task");
        type.setProperty("priority", 1L);
        type.setProperty("xmlElement", "serviceTask");
        type.setProperty("jcrNodeType", "wf:Activity");
        final Node definition =
            root.addNode("SystemWorkflows", "wf:SystemWorkflowsHomepage").addNode("noting", "wf:WorkflowDefinition");
        definition.setProperty("title", "Noting");
        final Node version = definition.addNode("v1", "wf:WorkflowVersion");
        version.setProperty("version", "1.0");
        version.setProperty("tags", new String[] {"active"});
        version.setProperty("targetResourceType", "wf/WorkflowsHomepage");
        final Node start = flowNode(version, "requested", "wf:StartEvent", type);
        start.setProperty("messageName", NOTE.getName());
        flowTo(start, "note");
        final Node task = flowNode(version, "note", "wf:Activity", type);
        task.setProperty("handler", "note");
        flowTo(task, "done");
        flowNode(version, "done", "wf:EndEvent", type);
        session.save();
        return this.context.resourceResolver().getResource("/Workflows");
    }

    private static Node flowNode(final Node version, final String name, final String primaryType, final Node type)
        throws RepositoryException
    {
        final Node node = version.addNode(name, primaryType);
        node.setProperty(ELEMENT_ID, name);
        node.setProperty(FLOW_NODE_TYPE, type);
        return node;
    }

    private static void flowTo(final Node from, final String to) throws RepositoryException
    {
        final Node flow = from.addNode("to" + to, "wf:SequenceFlow");
        flow.setProperty(ELEMENT_ID, from.getName() + "To" + to);
        flow.setProperty(TARGET_REF, to);
    }

    /**
     * A service task handler noting what the event said, as a workflow variable.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Noting implements ServiceTaskHandler
    {
        @Override
        public String getName()
        {
            return "note";
        }

        @Override
        public void execute(final WorkflowTaskContext context)
        {
            context.setVariable("noted", context.getEvent().get("what"));
        }
    }
}
