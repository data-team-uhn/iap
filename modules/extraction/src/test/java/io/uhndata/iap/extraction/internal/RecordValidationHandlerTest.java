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
import org.apache.sling.api.resource.ResourceWrapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private Resource submission;

    private Resource requirement;

    private Resource document;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        this.document = tree.createDocumentRequirement("proposal");
        this.requirement = tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");
        this.file = tree.createParsedFileFor(this.document, "A study of sleep.");
        setClaimedBy(JOB_ID);
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
    }

    private void setClaimedBy(final String jobId)
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
    }

    private Map<String, Object> createPayload(final String verdict, final double confidence)
    {
        return createPayload(verdict, confidence, true);
    }

    private Map<String, Object> createPayload(final String verdict, final double confidence, final boolean last)
    {
        return Map.of(ClaimReadingHandler.JOB_KEY, JOB_ID,
            RecordValidationHandler.REQUIREMENT_KEY, this.requirement.getPath(),
            RecordValidationHandler.FILE_KEY, this.file.getPath(),
            RecordValidationHandler.RESULT_KEY, new FieldResult(DocumentValidator.VERDICT_KEY, List.of(verdict),
                "It has aims and methods.", confidence, List.of(), 0),
            RecordValidationHandler.LAST_KEY, last);
    }

    private void record(final Map<String, Object> payload) throws Exception
    {
        record(SubmissionTree.withTypedCreates(this.submission), payload);
    }

    private void record(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.of(target, payload, new HashMap<>()));
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

    // The job's last yes leaves the reading running and lets the claim go, so the intake's next job can claim it
    @Test
    void savesTheLastYesAndLetsTheClaimGo() throws Exception
    {
        record(createPayload("yes", 0.9));

        assertEquals(1, countValidations());
        assertEquals(this.requirement.getPath(), getValidation().getRequirement().getPath());
        assertEquals(Validation.OUTCOME_YES, getValidation().getOutcome());
        assertEquals(this.file.getParent().getPath(),
            getValidation().getLatestExtraction().getSources().get(0).getPath());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.BLOCKED_PROPERTY));
    }

    // The job still has validations to ask
    @Test
    void keepsTheClaimAfterAYesThatIsNotTheLast() throws Exception
    {
        record(createPayload("yes", 0.9, false));

        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void endsTheReadingOnANoNamingTheDocument() throws Exception
    {
        record(createPayload("no", 0.9, false));

        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Unable to identify the proposal", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertTrue(getProperties().get(ExtractionStatus.BLOCKED_PROPERTY, Boolean.FALSE));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void namesTheValidationWhenItsDocumentIsNotInTheSchema() throws Exception
    {
        this.context.resourceResolver().delete(this.document);

        record(createPayload("no", 0.9));

        assertEquals("Unable to identify the Research proposal",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The gate must not guess: a no the model is not sure of does not block the request
    @Test
    void keepsReadingAfterANoItIsNotSureOf() throws Exception
    {
        record(createPayload("no", 0.5));

        assertEquals(Validation.OUTCOME_UNSURE, getValidation().getOutcome());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.BLOCKED_PROPERTY));
    }

    @Test
    void addsALaterRunToTheSameValidation() throws Exception
    {
        record(createPayload("no", 0.5));
        setClaimedBy(JOB_ID);

        record(createPayload("yes", 0.9));

        assertEquals(1, countValidations());
        assertEquals(2, getValidation().getExtractions().size());
        assertEquals(Validation.OUTCOME_YES, getValidation().getOutcome());
    }

    // After a Stop or a Read again, a late verdict must not land on the reading that follows
    @Test
    void refusesAReadingThatIsNoLongerThisJobs()
    {
        setClaimedBy("job-2");

        assertThrows(InvalidStateException.class, () -> record(createPayload("no", 0.9)));
        assertEquals(0, countValidations());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    @Test
    void refusesAnEventWithoutTheModelsAnswer()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", 0.9));
        payload.put(RecordValidationHandler.RESULT_KEY, "yes");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatDoesNotExist()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", 0.9));
        payload.put(RecordValidationHandler.REQUIREMENT_KEY, "/Schemas/proposal/v1/missing");

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesARequirementThatIsNotAValidation()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", 0.9));
        payload.put(RecordValidationHandler.REQUIREMENT_KEY, this.document.getPath());

        assertThrows(InvalidPayloadException.class, () -> record(payload));
    }

    @Test
    void refusesADocumentThatDoesNotExist()
    {
        final Map<String, Object> payload = new HashMap<>(createPayload("yes", 0.9));
        payload.put(RecordValidationHandler.FILE_KEY, this.file.getPath() + "-gone");

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

        assertThrows(PersistenceException.class, () -> record(readOnly, createPayload("yes", 0.9)));
    }
}
