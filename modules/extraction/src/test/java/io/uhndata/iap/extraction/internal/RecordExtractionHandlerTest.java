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
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RecordExtractionHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordExtractionHandlerTest
{
    private static final String JOB_ID = "job-1";

    private static final String KEY = "study.title";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordExtractionHandler handler = new RecordExtractionHandler();

    private Resource submission;

    private Resource question;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        this.question = tree.createQuestion("title", "The study's full title.");
        this.file = tree.createParsedFileFor(tree.createDocumentRequirement("proposal"), "Sleep in shift workers.");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, JOB_ID);
    }

    private ExtractedForm createForm(final String questionPath, final String filePath)
    {
        return new ExtractedForm(Map.of(KEY, questionPath),
            Map.of(KEY, new FieldResult(KEY, List.of("Sleep in shift workers"), "In the title.", 0.9, List.of(), 0)),
            List.of(filePath));
    }

    private Map<String, Object> createPayload(final Object forms)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ClaimReadingHandler.JOB_KEY, JOB_ID);
        payload.put(RecordExtractionHandler.FORMS_KEY, forms);
        return payload;
    }

    private Map<String, Object> createPayload()
    {
        return createPayload(List.of(createForm(this.question.getPath(), this.file.getPath())));
    }

    private void record(final Map<String, Object> payload) throws Exception
    {
        record(SubmissionTree.withTypedCreates(this.submission), payload);
    }

    private void record(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.of(target, payload, new HashMap<>()));
    }

    private Answer getAnswer()
    {
        return SubmissionFiles.getSubmission(this.submission).getAnswers().stream()
            .filter(answer -> answer.getQuestion() != null
                && this.question.getPath().equals(answer.getQuestion().getPath()))
            .findFirst()
            .orElse(null);
    }

    private ValueMap getProperties()
    {
        return this.submission.getValueMap();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("recordExtraction", this.handler.getName());
    }

    @Test
    void savesEachAnswerAsASuggestionAndEndsTheReading() throws Exception
    {
        record(createPayload());

        assertEquals(List.of("Sleep in shift workers"), getAnswer().getLatestExtraction().getExtractedValues());
        assertNull(getAnswer().getValue());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void endsWithTheNoteItIsGiven() throws Exception
    {
        final Map<String, Object> payload = createPayload();
        payload.put(RecordExtractionHandler.MESSAGE_KEY, "Some questions could not be read");

        record(payload);

        assertEquals("Some questions could not be read", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Edited out of the schema while the model was answering
    @Test
    void skipsAQuestionThatWentAway() throws Exception
    {
        record(createPayload(List.of(createForm(this.question.getPath() + "-gone", this.file.getPath()))));

        assertNull(getAnswer());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    // After a Stop or a Read again, late answers must not land on the reading that follows
    @Test
    void refusesAReadingThatIsNoLongerThisJobs()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-2");

        assertThrows(InvalidStateException.class, () -> record(createPayload()));
        assertNull(getAnswer());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    @Test
    void refusesAnEventWithoutTheForms()
    {
        assertThrows(InvalidPayloadException.class, () -> record(createPayload("forms")));
    }

    @Test
    void refusesAnEventCarryingSomethingElse()
    {
        assertThrows(InvalidPayloadException.class, () -> record(createPayload(List.of("a form"))));
    }

    @Test
    void refusesADocumentThatDoesNotExist()
    {
        assertThrows(InvalidPayloadException.class,
            () -> record(createPayload(List.of(createForm(this.question.getPath(), this.file.getPath() + "-gone")))));
    }

    @Test
    void failsWhenTheSubmissionCannotBeWritten()
    {
        final Resource readOnly = new ResourceWrapper(this.submission)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> record(readOnly, createPayload()));
    }
}
