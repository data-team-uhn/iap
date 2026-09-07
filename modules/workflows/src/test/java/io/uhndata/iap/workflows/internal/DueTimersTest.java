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

import java.lang.reflect.Field;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import javax.jcr.Workspace;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DueTimers}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DueTimersTest
{
    private static final String TASK = "/Submissions/request/wf:instances/timeOffRequest/approveRequest";

    private final SlingContext context = new SlingContext();

    private final DueTimers sweep = new DueTimers();

    private final Scheduler scheduler = Mockito.mock(Scheduler.class);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private QueryManager queries;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(TASK, "sling:resourceType", "wf/TaskInstance");
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        this.queries = Mockito.mock(QueryManager.class);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(this.queries);
        Mockito.when(session.getValueFactory()).thenReturn(Mockito.mock(ValueFactory.class));
        final ResourceResolver resolver = Mockito.spy(this.context.resourceResolver());
        Mockito.doReturn(session).when(resolver).adaptTo(Session.class);
        // Closing the spy would close the context's own resolver, which every later assertion reads through
        Mockito.doNothing().when(resolver).close();
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(resolver);

        inject("scheduler", this.scheduler);
        inject("resolverFactory", factory);
        inject("engine", this.engine);
        Mockito.when(this.scheduler.EXPR(Mockito.anyString())).thenReturn(Mockito.mock(ScheduleOptions.class));
    }

    @Test
    void schedulesItselfAndStandsDownAgain()
    {
        this.sweep.activate();
        this.sweep.deactivate();

        Mockito.verify(this.scheduler).schedule(Mockito.same(this.sweep), Mockito.any());
        Mockito.verify(this.scheduler).unschedule(Mockito.anyString());
    }

    @Test
    void deliversAPassedDeadlineAsATimeoutEvent() throws Exception
    {
        expectDue(TASK);

        this.sweep.run();

        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(Mockito.argThat(task -> TASK.equals(task.getPath())),
            event.capture());
        // Through the engine's own door, as an ordinary event: the clock is a translator like any other
        assertEquals("timeout", event.getValue().getName());
    }

    @Test
    void deliversNothingWhenNoDeadlineHasPassed() throws Exception
    {
        expectDue();

        this.sweep.run();

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void carriesOnAfterADeadlineThatCannotBeDelivered() throws Exception
    {
        expectDue(TASK, TASK);
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowFailedException("that workflow is broken", null))
            .thenReturn(null);

        this.sweep.run();

        // One broken definition must not stop every other deadline in the repository from being met
        Mockito.verify(this.engine, Mockito.times(2)).receiveEvent(Mockito.any(), Mockito.any());
    }

    @Test
    void survivesARepositoryThatCannotBeQueried() throws Exception
    {
        Mockito.when(this.queries.createQuery(Mockito.anyString(), Mockito.anyString()))
            .thenThrow(new RepositoryException("the index is gone"));

        assertDoesNotThrow(this.sweep::run);
        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void survivesAMissingServiceUser() throws Exception
    {
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        inject("resolverFactory", refusing);

        assertDoesNotThrow(this.sweep::run);
        Mockito.verifyNoInteractions(this.engine);
    }

    /**
     * Makes the deadline query answer with these tasks.
     *
     * @param paths the paths of the tasks whose deadline has passed
     * @throws RepositoryException never, only declared by the mocked JCR API
     */
    private void expectDue(final String... paths) throws RepositoryException
    {
        final Query query = Mockito.mock(Query.class);
        final QueryResult result = Mockito.mock(QueryResult.class);
        final NodeIterator nodes = Mockito.mock(NodeIterator.class);
        Mockito.when(this.queries.createQuery(Mockito.anyString(), Mockito.anyString())).thenReturn(query);
        Mockito.when(query.execute()).thenReturn(result);
        Mockito.when(result.getNodes()).thenReturn(nodes);
        Boolean[] remaining = new Boolean[paths.length + 1];
        for (int i = 0; i < paths.length; i++) {
            remaining[i] = true;
        }
        remaining[paths.length] = false;
        Mockito.when(nodes.hasNext()).thenReturn(remaining[0], java.util.Arrays.copyOfRange(remaining, 1,
            remaining.length));
        if (paths.length > 0) {
            final Node[] found = new Node[paths.length];
            for (int i = 0; i < paths.length; i++) {
                found[i] = Mockito.mock(Node.class);
                Mockito.when(found[i].getPath()).thenReturn(paths[i]);
            }
            Mockito.when(nodes.nextNode()).thenReturn(found[0],
                java.util.Arrays.copyOfRange(found, 1, found.length));
        }
    }

    private void inject(final String name, final Object value) throws ReflectiveOperationException
    {
        final Field field = DueTimers.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.sweep, value);
    }
}
