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

import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowStarter}: finding the workflow an entity's own data points at, and refusing
 * clearly rather than half-starting when it does not.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowStarterTest
{
    private static final String HOST = "/Submissions/aLongWeekend";

    private static final String VERSION = "/Workflows/timeOffRequest/v1";

    private static final String CHAIN = "workflowFrom";

    // JCR-backed: following a reference means asking the repository for a node by identifier
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(HOST, TYPE, "sub/Submission");
        this.context.create().resource(HOST + "/wf:instances", TYPE, "wf/WorkflowInstances");
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request", "active", true));
        this.context.create().resource(VERSION, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        // Start straight to an end: this suite is about finding the workflow, not about running it
        this.context.create().resource(VERSION + "/start", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "start"));
        this.context.create().resource(VERSION + "/start/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "toDone", "targetRef", "done"));
        this.context.create().resource(VERSION + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "done"));
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhereToLook()
    {
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context(null, HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(rejection.getMessage().contains(CHAIN));
    }

    @Test
    void refusesABlankChain()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context(" ", HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
    }

    @Test
    void startsTheWorkflowOnWhateverTheRunJustCreated() throws Exception
    {
        reference(HOST, "workflow", VERSION);
        WorkflowStarter.execute(context("workflow", "/Submissions", HOST), performer(),
            EngineFixture.conditions(), EngineFixture.principals());

        // The created entity, not the homepage the event was aimed at, is what ends up under the workflow
        assertNotNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    @Test
    void refusesWhenNothingIsAtTheRecordedPath()
    {
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context("workflow", "/Submissions", "/Submissions/vanished"),
                performer(), EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(rejection.getMessage().contains("Nothing was created"));
    }

    @Test
    void doesNothingWhenTheChainLeadsNowhere() throws Exception
    {
        // No `workflow` property at all: an entity with no workflow is a perfectly ordinary entity
        assertDoesNotThrow(
            () -> WorkflowStarter.execute(context("workflow", HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    @Test
    void doesNothingWhenTheChainDangles() throws Exception
    {
        // A reference to something that has since been removed, which a repository reports as simply not there
        this.context.resourceResolver().getResource(HOST)
            .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
            .put("workflow", "1e17e5b1-0000-0000-0000-000000000000");

        assertDoesNotThrow(
            () -> WorkflowStarter.execute(context("workflow", HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    @Test
    void doesNothingWhenTheChainEndsSomewhereElse() throws Exception
    {
        // A real reference, but not to a workflow version — the entity is simply not under a workflow
        reference(HOST, "workflow", "/Workflows/timeOffRequest");

        assertDoesNotThrow(
            () -> WorkflowStarter.execute(context("workflow", HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    @Test
    void refusesAnInactiveWorkflow() throws Exception
    {
        reference(HOST, "workflow", VERSION);
        this.context.resourceResolver().getResource(VERSION)
            .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("active", false);

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context("workflow", HOST), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(rejection.getMessage().contains("not active"));
    }

    @Test
    void refusesAHostThatCannotHoldWorkflows() throws Exception
    {
        this.context.create().resource("/Submissions/plain", TYPE, "sub/Submission");
        reference("/Submissions/plain", "workflow", VERSION);

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context("workflow", "/Submissions/plain"), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(rejection.getMessage().contains("cannot hold workflows"));
    }

    @Test
    void doesNothingWhenTheRepositoryAnswersWithNothing() throws Exception
    {
        // Not every repository signals a missing node the same way: some throw, some simply return nothing
        reference(HOST, "workflow", VERSION);
        final Session empty = Mockito.mock(Session.class);
        Mockito.when(empty.getNodeByIdentifier(Mockito.anyString())).thenReturn(null);

        assertDoesNotThrow(() -> WorkflowStarter.execute(
            context("workflow", HOST, null, sessionOf(empty)), performer(), EngineFixture.conditions(),
            EngineFixture.principals()));
        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    @Test
    void reportsARepositoryThatWillNotHoldTheReference() throws Exception
    {
        // The instance must point at the version it came from; a repository that refuses that leaves an instance
        // that could never be resumed, so the run has to fail rather than press on
        reference(HOST, "workflow", VERSION);
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource real = super.getResource(path);
                if (real == null || !VERSION.equals(path)) {
                    return real;
                }
                return new org.apache.sling.api.resource.ResourceWrapper(real)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> WorkflowStarter.execute(context("workflow", HOST, null, sabotaged), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(failure.getMessage().contains("point the instance at its workflow version"));
    }

    @Test
    void reportsARepositoryThatCannotFollowReferences() throws Exception
    {
        reference(HOST, "workflow", VERSION);
        final Session broken = Mockito.mock(Session.class);
        Mockito.when(broken.getNodeByIdentifier(Mockito.anyString()))
            .thenThrow(new RepositoryException("the identifier index is corrupt"));
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> WorkflowStarter.execute(context("workflow", HOST, null, sessionOf(broken)), performer(),
                EngineFixture.conditions(), EngineFixture.principals()));
        assertTrue(rejection.getMessage().contains("usable reference"));
    }

    /**
     * Writes a real REFERENCE, which is the only kind a chain can follow.
     *
     * @param from the resource holding it
     * @param property the property name
     * @param to the referenced resource's path
     * @throws Exception when the repository refuses
     */
    private void reference(final String from, final String property, final String to) throws Exception
    {
        final Node source = this.context.resourceResolver().getResource(from).adaptTo(Node.class);
        final Node target = this.context.resourceResolver().getResource(to).adaptTo(Node.class);
        source.setProperty(property, target);
        this.context.resourceResolver().commit();
    }

    /**
     * A performer that refuses to be needed: these workflows have no service tasks.
     *
     * @return a performer
     */
    private InstanceRunner.ServiceTaskPerformer performer()
    {
        return (activity, instance) -> {
            throw new IllegalStateException("No service task was expected here");
        };
    }

    private WorkflowTaskContext context(final String chain, final String target)
    {
        return context(chain, target, null, repository());
    }

    private WorkflowTaskContext context(final String chain, final String target, final String created)
    {
        return context(chain, target, created, repository());
    }

    /**
     * A resolver whose session is the given one, for testing how odd repository answers are handled.
     *
     * @param session what the resolver adapts to
     * @return a resolver over that session
     */
    private ResourceResolver sessionOf(final Session session)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? type.cast(session) : super.adaptTo(type);
            }
        };
    }

    /**
     * A resolver wrapped so the user store is reachable, which the mock repository does not provide.
     *
     * @return a session that can also answer who the repository's users are, which granting read needs
     */
    private ResourceResolver repository()
    {
        return EngineFixture.withRepositoryServices(this.context.resourceResolver());
    }

    /**
     * A task context for an activity configured with the given chain.
     *
     * @param chain the {@code workflowFrom} configuration, or {@code null} for an activity that omits it
     * @param target the resource the event was aimed at
     * @param created what the run reports having created, or {@code null} for a run that created nothing
     * @param resolver the session to work through
     * @return the assembled context
     */
    private WorkflowTaskContext context(final String chain, final String target, final String created,
        final ResourceResolver resolver)
    {
        final Map<String, Object> config = new HashMap<>(Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "start", "handler", WorkflowStarter.NAME));
        if (chain != null) {
            config.put(CHAIN, chain);
        }
        final Resource activityResource =
            this.context.create().resource("/Workflows/config" + config.hashCode(), config);
        final Activity activity = activityResource.adaptTo(Activity.class);
        final Map<String, Object> variables = new HashMap<>();
        if (created != null) {
            variables.put(WorkflowResult.CREATED_PATH, created);
        }
        final Resource host = resolver.getResource(target);
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return host;
            }

            @Override
            public String getActor()
            {
                return "demo-requester";
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return new WorkflowEvent("create", Map.of());
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public Object getVariable(final String name)
            {
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }
}
