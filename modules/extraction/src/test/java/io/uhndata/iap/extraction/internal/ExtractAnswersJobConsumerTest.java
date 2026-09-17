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

import java.lang.reflect.Field;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer.JobResult;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractAnswersJobConsumer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractAnswersJobConsumerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final Job job = Mockito.mock(Job.class);

    private final ExtractAnswersJobConsumer consumer = new ExtractAnswersJobConsumer();

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.schemaVersion();
        this.submission = tree.submission();
        inject("resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject("engine", this.engine);
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ExtractAnswersJobConsumer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.consumer, value);
    }

    private void jobFor(final String path)
    {
        Mockito.when(this.job.getProperty(ExtractAnswersJobConsumer.SUBMISSION, String.class)).thenReturn(path);
    }

    @Test
    void firesTheEventOnTheSubmission() throws Exception
    {
        jobFor(this.submission.getPath());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final ArgumentCaptor<Resource> target = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(target.capture(), event.capture());
        assertEquals(this.submission.getPath(), target.getValue().getPath());
        assertEquals("extractAnswers", event.getValue().getName());
        assertTrue(event.getValue().getPayload().isEmpty());
    }

    @Test
    void dropsAJobThatNamesNoSubmission()
    {
        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void dropsAJobForASubmissionThatIsGone()
    {
        jobFor("/Submissions/aa/bb/cc/gone");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void doesNotRetryWhenTheEngineRefuses() throws Exception
    {
        jobFor(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new NotAuthorizedException("not yours"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void dropsTheJobWithoutTheServiceUser() throws Exception
    {
        jobFor(this.submission.getPath());
        inject("resolverFactory", new TestResolverFactory(null));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }
}
