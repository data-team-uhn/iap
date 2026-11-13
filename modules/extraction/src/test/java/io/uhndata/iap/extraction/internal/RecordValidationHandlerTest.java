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
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Validation;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RecordValidationHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordValidationHandlerTest
{
    private static final String JOB_ID = "job-1";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordValidationHandler handler = new RecordValidationHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource requirement;

    private Resource document;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.document = this.tree.createDocumentRequirement("proposal");
        this.requirement = this.tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");
        this.file = this.tree.createParsedFileFor(this.document, "A study of sleep.");
        this.tree.startReadingAs(JOB_ID);
    }

    private Map<String, Object> createPayload(final String verdict, final double confidence)
    {
        return Map.of(ReadingEvents.JOB_KEY, JOB_ID,
            ReadingEvents.REQUIREMENT_KEY, this.requirement.getPath(),
            ReadingEvents.FILE_KEY, this.file.getPath(),
            ReadingEvents.RESULT_KEY, new FieldResult(DocumentValidator.VERDICT_KEY, List.of(verdict),
                "It has aims and methods.", confidence, confidence, List.of(), 0));
    }

    private void record(final Map<String, Object> payload) throws Exception
    {
        record(SubmissionTree.wrapWithTypedCreates(this.submission), payload);
    }

    private void record(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.createContext(target, payload, new HashMap<>()));
    }

    private Validation getValidation()
    {
        return SubmissionFiles.getSubmission(this.submission)
            .findValidation(this.requirement.adaptTo(ValidationRequirement.class));
    }

    private long countValidations()
    {
        return StreamSupport.stream(this.submission.getChildren().spliterator(), false)
            .filter(child -> child.isResourceType(Validation.RESOURCE_TYPE))
            .count();
    }

    private ValueMap getProperties()
    {
        return this.submission.getValueMap();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("recordValidation", this.handler.getName());
    }

    // A yes leaves the reading running and the claim with the job, for its next step
    @Test
    void savesAYesAndKeepsTheClaim() throws Exception
    {
        record(createPayload("yes", SubmissionTree.SURE));

        assertEquals(1, countValidations());
        assertEquals(this.requirement.getPath(), getValidation().getRequirement().getPath());
        assertEquals(Validation.OUTCOME_YES, getValidation().getOutcome());
        assertEquals(this.file.getParent().getPath(),
            getValidation().getLatestExtraction().getSources().get(0).getPath());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    // The job hands the no to the intake first, and only then ends the reading, so a lost hand-over is retried
    @Test
    void savesANoAndKeepsTheReadingForTheHandOver() throws Exception
    {
        record(createPayload("no", SubmissionTree.SURE));

        assertEquals(Validation.OUTCOME_NO, getValidation().getOutcome());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    // The model must not guess, so the chain reads a no it is not sure of as no answer
    @Test
    void savesANoItIsNotSureOfAsUnsure() throws Exception
    {
        record(createPayload("no", SubmissionTree.UNSURE));

        assertEquals(Validation.OUTCOME_UNSURE, getValidation().getOutcome());
    }

    @Test
    void addsALaterRunToTheSameValidation() throws Exception
    {
        record(createPayload("no", SubmissionTree.UNSURE));

        record(createPayload("yes", SubmissionTree.SURE));

        assertEquals(1, countValidations());
        assertEquals(2, getValidation().getExtractions().size());
        assertEquals(Validation.OUTCOME_YES, getValidation().getOutcome());
    }

    // After a Stop or a Read again, a late verdict must not land on the reading that follows
    @Test
    void refusesAReadingThatIsNoLongerThisJobs()
    {
        this.tree.startReadingAs("job-2");

        assertThrows(InvalidStateException.class, () -> record(createPayload("no", SubmissionTree.SURE)));
        assertEquals(0, countValidations());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    // A Stop takes the claim down; a verdict arriving after it must not land on the stopped reading
    @Test
    void refusesAReadingNobodyHolds()
    {
        this.submission.adaptTo(ModifiableValueMap.class).remove(ExtractionStatus.READING_CLAIMED_BY_PROPERTY);

        assertThrows(InvalidStateException.class, () -> record(createPayload("no", SubmissionTree.SURE)));
        assertEquals(0, countValidations());
    }

    @Test
    void refusesAnEventWithoutTheModelsAnswer()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", SubmissionTree.SURE));
        payload.put(ReadingEvents.RESULT_KEY, "yes");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatDoesNotExist()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", SubmissionTree.SURE));
        payload.put(ReadingEvents.REQUIREMENT_KEY, "/Schemas/proposal/v1/missing");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatIsNotAValidation()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", SubmissionTree.SURE));
        payload.put(ReadingEvents.REQUIREMENT_KEY, this.document.getPath());

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesADocumentThatDoesNotExist()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", SubmissionTree.SURE));
        payload.put(ReadingEvents.FILE_KEY, this.file.getPath() + "-gone");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void failsWhenTheSubmissionCannotBeWritten()
    {
        assertThrows(PersistenceException.class,
            () -> record(SubmissionTree.wrapReadOnly(this.submission), createPayload("yes", SubmissionTree.SURE)));
    }
}
