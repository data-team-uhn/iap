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
package io.uhndata.iap.extraction.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

/**
 * Runs a reading job against a stand-in engine. Each event runs the step the test put for it, through the test's
 * resolver, while the job reads through a {@link LaggingResolver}: as in production, the job sees a step only
 * after it refreshes.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ReadingHarness
{
    /** The id of the job the harness runs. */
    static final String JOB_ID = "job-1";

    /** The id of a job that took the reading over, such as after a Read again. */
    static final String OTHER_JOB_ID = "job-2";

    private final SlingContext context;

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final ReadingJobs jobs = new ReadingJobs();

    private final RequirementReader reader = new RequirementReader();

    /** What the stand-in engine does for each event, by name. */
    private final Map<String, Answer<Object>> steps = new HashMap<>();

    /**
     * Wires the reading's components to the stand-in engine and model. The claim and give-up steps are set up;
     * the test puts the steps of its own job.
     *
     * @param context the test's context, holding the submission
     */
    ReadingHarness(final SlingContext context)
    {
        this.context = context;
        final ReadingRuns runs = new ReadingRuns();
        TestComponents.inject(this.jobs, "resolverFactory",
            new TestResolverFactory(new LaggingResolver(context.resourceResolver())));
        TestComponents.inject(this.jobs, "engine", this.engine);
        TestComponents.inject(this.jobs, "runs", runs);
        TestComponents.inject(this.reader, "intake", this.intake);
        TestComponents.inject(this.reader, "documents", new ParsedDocuments());
        TestComponents.inject(this.reader, "runs", runs);
        putStep(ReadingJobs.READING_STARTED_EVENT, new ClaimReadingHandler());
        putStep(ReadingJobs.READING_FAILED_EVENT, new GiveUpReadingHandler());
        try {
            Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(
                call -> this.steps.get(call.<WorkflowEvent>getArgument(1).getName()).answer(call));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    WorkflowEngine getEngine()
    {
        return this.engine;
    }

    AnswerIntakeService getIntake()
    {
        return this.intake;
    }

    ReadingJobs getJobs()
    {
        return this.jobs;
    }

    RequirementReader getReader()
    {
        return this.reader;
    }

    /**
     * Creates the job the consumer is handed for the submission.
     *
     * @param submission the submission's path
     * @return a job with {@link #JOB_ID}
     */
    static Job createJob(final String submission)
    {
        final Job job = Mockito.mock(Job.class);
        Mockito.when(job.getId()).thenReturn(JOB_ID);
        Mockito.when(job.getProperty(ReadingJobs.SUBMISSION_PROPERTY, String.class)).thenReturn(submission);
        return job;
    }

    /** Runs the handler for the event, as the engine would. */
    void putStep(final String event, final ServiceTaskHandler handler)
    {
        this.steps.put(event, call -> runStep(handler, call));
    }

    /** Does what the test says for the event, in place of a handler. */
    void putStep(final String event, final Answer<Object> step)
    {
        this.steps.put(event, step);
    }

    /**
     * Runs a step as the engine would: on the target, committed when the step succeeds. The engine reverts a step
     * that throws, which jcr-mock cannot do, so a step that throws after writing fails the test instead.
     *
     * @param handler the step's handler
     * @param call the engine call that fired the event
     * @return nothing, as the engine returns
     * @throws Exception whatever the handler threw
     */
    Object runStep(final ServiceTaskHandler handler, final InvocationOnMock call) throws Exception
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.commit();
        final Resource target = SubmissionTree.wrapWithTypedCreates(
            resolver.getResource(call.<Resource>getArgument(0).getPath()));
        try {
            handler.execute(TaskContexts.createContext(target, call.<WorkflowEvent>getArgument(1).getPayload(),
                new HashMap<>()));
        } catch (final Exception e) {
            if (resolver.hasChanges()) {
                throw new AssertionError("The failed step left writes the engine would revert", e);
            }
            throw e;
        }
        resolver.commit();
        return null;
    }

    /**
     * Completes a task as the engine would, so the intake moves on to its next one.
     *
     * @param call the engine call that fired the event at the task
     * @return nothing, as the engine returns
     * @throws Exception if the task cannot be saved
     */
    Object closeTask(final InvocationOnMock call) throws Exception
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.getResource(call.<Resource>getArgument(0).getPath()).adaptTo(ModifiableValueMap.class)
            .put("status", "completed");
        resolver.commit();
        return null;
    }

    /** Sets the submission's status back to running and forgets the calls so far, as a new reading starts. */
    void restartReading() throws Exception
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.getResource(SubmissionTree.SUBMISSION_PATH).adaptTo(ModifiableValueMap.class)
            .put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING);
        resolver.commit();
        Mockito.clearInvocations(this.engine, this.intake);
    }

    List<WorkflowEvent> getFiredEvents() throws Exception
    {
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.atLeast(0)).receiveEvent(Mockito.any(), events.capture());
        return events.getAllValues();
    }

    List<String> getFiredEventNames() throws Exception
    {
        return getFiredEvents().stream().map(WorkflowEvent::getName).toList();
    }

    /** The groups of questions of each call to the model, in order. */
    List<List<ExtractionPrompt.Group>> getAskedGroups()
    {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake, Mockito.atLeast(0)).askQuestions(Mockito.any(), Mockito.any(), groups.capture(),
            Mockito.any());
        return groups.getAllValues();
    }
}
