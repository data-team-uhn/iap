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
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReadAgainHandler}: which step it runs again, and when the submitter may ask.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadAgainHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final ReadAgainHandler handler = new ReadAgainHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource category;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"),
            "A study of sleep in shift workers.");
        this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH).adaptTo(ModifiableValueMap.class)
            .put("documents", new String[] { "proposal" });
        this.tree.createQuestion("title", "The study's full title.");
        this.category = this.tree.createClassificationQuestion("study_kind", "proposal", "What kind of study?",
            "interventional", "observational");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "The model could not be reached");
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, true);
        final ReadingRequests requests = new ReadingRequests();
        inject(requests, "jobManager", this.jobManager);
        inject(this.handler, "requests", requests);
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private static void inject(final Object component, final String name, final Object value) throws Exception
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(component, value);
    }

    private Resource answer(final Resource question, final String value)
    {
        final Resource answer = this.context.create().resource(
            SubmissionTree.SUBMISSION_PATH + "/a-" + question.getName(), Map.of("jcr:primaryType", "sub:Answer",
                "sling:resourceType", "sub/Answer", "value", new String[] { value }));
        SubmissionTree.reference(answer, "question", question);
        return answer;
    }

    /** The model read the category before, and the submitter has not confirmed it yet. */
    private void readCategory()
    {
        final Resource answer = answer(this.category, "");
        this.context.create().resource(answer.getPath() + "/e1", Map.of("jcr:primaryType", "sub:Extraction",
            "sling:resourceType", "sub/Extraction", "extractedValues", "interventional"));
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.of(this.submission, Map.of(), new HashMap<>());
    }

    private void verifyQueued(final String topic)
    {
        Mockito.verify(this.jobManager).addJob(topic, Map.of(ReadingJobs.SUBMISSION_PROPERTY,
            this.submission.getPath(), ReadingJobs.REQUESTED_PROPERTY, Boolean.TRUE));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("readAgain", this.handler.getName());
    }

    // The categorisation never ran, such as when the model could not be reached
    @Test
    void runsTheDocumentChecksWhenACategoryWasNeverRead() throws Exception
    {
        this.handler.execute(createTask());

        verifyQueued(DecisionJobConsumer.JOB_TOPIC);
        final ValueMap recorded = this.submission.getValueMap();
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNull(recorded.get(ExtractionStatus.MESSAGE_PROPERTY, String.class),
            "the old failure is no longer the news");
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED_PROPERTY),
            "or the job would turn itself away");
    }

    @Test
    void runsTheDocumentChecksWhenAValidationHasNoVerdict() throws Exception
    {
        answer(this.category, "interventional");
        this.tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");

        this.handler.execute(createTask());

        verifyQueued(DecisionJobConsumer.JOB_TOPIC);
    }

    @Test
    void refusesUntilTheCategoryIsConfirmed()
    {
        readCategory();

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE, refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void readsTheAnswersOnceTheCategoryIsConfirmed() throws Exception
    {
        answer(this.category, "interventional");

        this.handler.execute(createTask());

        verifyQueued(MainExtractionJobConsumer.JOB_TOPIC);
    }

    // A question read once is not asked again, found or not
    @Test
    void refusesWhenEveryQuestionWasRead()
    {
        answer(this.category, "interventional");
        answer(this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"), "Sleep");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(ReadAgainHandler.NOTHING_LEFT_MESSAGE, refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesWhileAReadingIsAlreadyGoing()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    // The same document gets the same answer: only a new one helps
    @Test
    void refusesAfterADocumentWasRejected()
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.BLOCKED_PROPERTY, true);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "Unable to identify the proposal");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals("Unable to identify the proposal", refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesARejectedRequestEvenWithoutAMessage()
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.BLOCKED_PROPERTY, true);
        properties.remove(ExtractionStatus.MESSAGE_PROPERTY);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesARequestAlreadySent()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("tags", new String[0]);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
    }

    @Test
    void saysSoWhenTheReadingCannotBeQueued()
    {
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap())).thenReturn(null);

        assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
    }
}
