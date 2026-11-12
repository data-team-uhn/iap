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
 * Unit tests for {@link RecordCategoryHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordCategoryHandlerTest
{
    private static final String JOB_ID = "job-1";

    private static final String KEY = "classification.study_kind";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordCategoryHandler handler = new RecordCategoryHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource question;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        final Resource document = this.tree.createDocumentRequirement("proposal");
        this.question = this.tree.createClassificationQuestion("study_kind", "proposal", "What kind of study?",
            "interventional", "observational");
        this.file = this.tree.createParsedFileFor(document, "A study of sleep.");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, JOB_ID);
    }

    private Map<String, Object> createPayload(final FieldResult result)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ClaimReadingHandler.JOB_KEY, JOB_ID);
        payload.put(RecordCategoryHandler.REQUIREMENT_KEY, SubmissionTree.VERSION_PATH + "/study_kind");
        payload.put(RecordCategoryHandler.FILE_KEY, this.file.getPath());
        payload.put(RecordCategoryHandler.RESULT_KEY, result);
        return payload;
    }

    private static FieldResult createPick(final String category)
    {
        return new FieldResult(KEY, List.of(category), "It assigns treatments.", 0.9, List.of(), 0);
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

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("recordCategory", this.handler.getName());
    }

    // A suggestion only: the submitter confirms it
    @Test
    void savesThePickAsASuggestion() throws Exception
    {
        record(createPayload(createPick("interventional")));

        assertEquals(List.of("interventional"), getAnswer().getLatestExtraction().getExtractedValues());
        assertNull(getAnswer().getValue());
        assertEquals(JOB_ID, this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void savesAPickNotFound() throws Exception
    {
        record(createPayload(FieldResult.createNotFound(KEY)));

        assertEquals(List.of(), getAnswer().getLatestExtraction().getExtractedValues());
    }

    // After a Stop or a Read again, a late pick must not land on the reading that follows
    @Test
    void refusesAReadingThatIsNoLongerThisJobs()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-2");

        assertThrows(InvalidStateException.class, () -> record(createPayload(createPick("interventional"))));
        assertNull(getAnswer());
    }

    @Test
    void refusesAnEventWithoutThePick()
    {
        final Map<String, Object> payload = createPayload(createPick("interventional"));
        payload.put(RecordCategoryHandler.RESULT_KEY, "interventional");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatDoesNotExist()
    {
        final Map<String, Object> payload = createPayload(createPick("interventional"));
        payload.put(RecordCategoryHandler.REQUIREMENT_KEY, SubmissionTree.VERSION_PATH + "/missing");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatIsNotAClassification()
    {
        final Map<String, Object> payload = createPayload(createPick("interventional"));
        payload.put(RecordCategoryHandler.REQUIREMENT_KEY, SubmissionTree.VERSION_PATH + "/proposal");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesAClassificationWithoutAQuestion() throws Exception
    {
        this.context.resourceResolver().delete(this.question);

        assertThrows(InvalidPayloadException.class, () -> record(createPayload(createPick("interventional"))));
    }

    @Test
    void refusesADocumentThatDoesNotExist()
    {
        final Map<String, Object> payload = createPayload(createPick("interventional"));
        payload.put(RecordCategoryHandler.FILE_KEY, this.file.getPath() + "-gone");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
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

        assertThrows(PersistenceException.class, () -> record(readOnly, createPayload(createPick("interventional"))));
    }
}
