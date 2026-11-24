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
package io.uhndata.iap.demos.timeoff.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextBuilder;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.utils.testing.TestResolverFactory;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.testing.WorkflowPlugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NightlyUrgencySweep}: which requests it finds, what it sends them, and how it carries on past
 * failures. What the event does is the urgency rule, tested in {@link TimeOffUrgencyTest}.
 *
 * <p>The query is stubbed, since the mock repository does not run JCR-SQL2. What this class owns is what it does
 * with the results.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NightlyUrgencySweepTest
{
    private static final String MINE = "/Submissions/mine";

    private static final String OTHER = "/Submissions/other";

    private static final String JOB_NAME = "iap-demo-time-off-urgency";

    private final SlingContext context = new SlingContextBuilder().plugin(WorkflowPlugins.MOCK_ENGINE).build();

    private final Scheduler scheduler = Mockito.mock(Scheduler.class);

    private final ScheduleOptions options = Mockito.mock(ScheduleOptions.class);

    private final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);

    private NightlyUrgencySweep sweep;

    private WorkflowEngine engine;

    /** The service users the sweep logs in as, which a test chooses. */
    private ResourceResolverFactory serviceUsers;

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(MINE);
        this.context.create().resource(OTHER);
        this.serviceUsers = TestResolverFactory.serving(this.context.resourceResolver());
        // Only the nightly schedule is answered, so the sweep cannot start on any other
        Mockito.when(this.scheduler.EXPR("0 5 0 * * ?")).thenReturn(this.options);
        this.context.registerService(Scheduler.class, this.scheduler, Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        this.context.registerService(ResourceResolverFactory.class,
            TestResolverFactory.answeredBy(authenticationInfo ->
                this.serviceUsers.getServiceResourceResolver(authenticationInfo)),
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        this.engine = this.context.getService(WorkflowEngine.class);
        this.sweep = this.context.registerInjectActivateService(NightlyUrgencySweep.class);
        ErrorLogger.setService(this.recorder);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.recorder);
    }

    @Test
    void asksEveryWaitingRequestToBeJudgedAgain() throws WorkflowException
    {
        this.sweep.sweep(resolverFinding(MINE, OTHER));

        final ArgumentCaptor<Resource> targets = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.times(2)).receiveEvent(targets.capture(), events.capture());
        assertEquals(List.of(MINE, OTHER), targets.getAllValues().stream().map(Resource::getPath).toList());
        assertTrue(events.getAllValues().stream().allMatch(event -> "reassessTimeOffUrgency".equals(event.getName())));
    }

    @Test
    void asksForTheSentRequestsItsProcessRuns()
    {
        final QueryRecordingResolver recording = new QueryRecordingResolver(this.context.resourceResolver());

        this.sweep.sweep(recording);

        assertEquals(List.of("SELECT * FROM [sub:Submission] AS submission"
            + " WHERE submission.[budgetRemainingDays] IS NOT NULL AND submission.[tags] = 'submitted'"),
            recording.queries());
    }

    @Test
    void carriesOnPastARequestThatHasGoneAway() throws WorkflowException
    {
        // The query still returns a request deleted since it ran
        this.sweep.sweep(resolverFinding("/Submissions/gone", MINE));

        Mockito.verify(this.engine).receiveEvent(Mockito.argThat(target -> MINE.equals(target.getPath())),
            Mockito.any());
        Mockito.verifyNoMoreInteractions(this.engine);
    }

    @Test
    void carriesOnPastARequestTheEngineRefuses() throws WorkflowException
    {
        refuse(MINE, new NoApplicableWorkflowException("nothing waits for it"));

        this.sweep.sweep(resolverFinding(MINE, OTHER));

        Mockito.verify(this.engine).receiveEvent(Mockito.argThat(target -> OTHER.equals(target.getPath())),
            Mockito.any());
    }

    @Test
    void carriesOnPastAnUnexpectedFailure() throws WorkflowException
    {
        refuse(MINE, new IllegalStateException("The tags service is not available"));

        this.sweep.sweep(resolverFinding(MINE, OTHER));

        Mockito.verify(this.engine).receiveEvent(Mockito.argThat(target -> OTHER.equals(target.getPath())),
            Mockito.any());
    }

    @Test
    void recordsAllItsFailuresAsOne() throws WorkflowException
    {
        final NoApplicableWorkflowException first = new NoApplicableWorkflowException("nothing waits for it");
        refuse(MINE, first);
        refuse(OTHER, new NoApplicableWorkflowException("nor for this one"));

        this.sweep.sweep(resolverFinding(MINE, OTHER));

        final ArgumentCaptor<ErrorContext> recorded = ArgumentCaptor.forClass(ErrorContext.class);
        Mockito.verify(this.recorder).logError(Mockito.same(first), recorded.capture());
        assertEquals(MINE, recorded.getValue().getSubject());
        assertEquals("2", recorded.getValue().getDetails().get("failedRequests"));
    }

    @Test
    void recordsNothingWhenEveryRequestIsJudged()
    {
        this.sweep.sweep(resolverFinding(MINE, OTHER));

        Mockito.verifyNoInteractions(this.recorder);
    }

    @Test
    void schedulesItselfNightlyOnOneInstanceAndStandsDownAgain()
    {
        this.sweep.deactivate();

        Mockito.verify(this.options).name(JOB_NAME);
        Mockito.verify(this.options).canRunConcurrently(false);
        Mockito.verify(this.options).onLeaderOnly(true);
        Mockito.verify(this.scheduler).schedule(this.sweep, this.options);
        Mockito.verify(this.scheduler).unschedule(JOB_NAME);
    }

    @Test
    void judgesEveryRequestWhenTheClockCallsIt() throws Exception
    {
        this.serviceUsers = TestResolverFactory.serving(resolverFinding(MINE));

        this.sweep.run();

        Mockito.verify(this.engine).receiveEvent(Mockito.argThat(target -> MINE.equals(target.getPath())),
            Mockito.any());
    }

    @Test
    void recordsAMissingServiceUser() throws Exception
    {
        final LoginException missing = new LoginException("no such service user");
        this.serviceUsers = TestResolverFactory.answeredBy(authenticationInfo -> {
            throw missing;
        });

        this.sweep.run();

        Mockito.verify(this.recorder).logError(Mockito.same(missing), Mockito.any());
        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void recordsAQueryThatFails() throws Exception
    {
        final IllegalStateException failure = new IllegalStateException("the query timed out");
        this.serviceUsers = TestResolverFactory.serving(new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                throw failure;
            }
        });

        this.sweep.run();

        Mockito.verify(this.recorder).logError(Mockito.same(failure), Mockito.any());
    }

    private void refuse(final String path, final Exception refusal) throws WorkflowException
    {
        Mockito.when(this.engine.receiveEvent(
            Mockito.argThat(target -> target != null && path.equals(target.getPath())), Mockito.any()))
            .thenThrow(refusal);
    }

    /** A resolver whose query answers with exactly these paths, in this order. */
    private ResourceResolver resolverFinding(final String... paths)
    {
        final ResourceResolver delegate = this.context.resourceResolver();
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                // A path whose resource has gone is still a row the query returns, so it is handed over
                return List.of(paths).stream()
                    .map(path -> {
                        final Resource found = delegate.getResource(path);
                        if (found != null) {
                            return found;
                        }
                        final Resource stale = Mockito.mock(Resource.class);
                        Mockito.when(stale.getPath()).thenReturn(path);
                        return stale;
                    })
                    .iterator();
            }
        };
    }

    /** A resolver that notes down every query it is asked, and finds nothing. */
    private static final class QueryRecordingResolver extends ResourceResolverWrapper
    {
        private final List<String> queries = new ArrayList<>();

        QueryRecordingResolver(final ResourceResolver delegate)
        {
            super(delegate);
        }

        @Override
        public Iterator<Resource> findResources(final String query, final String language)
        {
            this.queries.add(query);
            return Collections.emptyIterator();
        }

        List<String> queries()
        {
            return this.queries;
        }
    }
}
