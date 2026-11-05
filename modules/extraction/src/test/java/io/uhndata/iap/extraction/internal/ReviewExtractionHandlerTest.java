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

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReviewExtractionHandler}: what the submitter's two verdicts change, and what they
 * deliberately leave alone.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReviewExtractionHandlerTest
{
    private static final String AIMS = "study/aims";

    private static final String TYPE = "jcr:primaryType";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ReviewExtractionHandler handler = new ReviewExtractionHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource aims;

    private Resource extraction;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.aims = this.tree.createQuestion("aims", "Find the primary aims.");
        this.submission = this.tree.createSubmission();
        final Resource answer = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of(TYPE, "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "reduce readmissions" }));
        SubmissionTree.reference(answer, "question", this.aims);
        this.extraction = this.context.create().resource(answer.getPath() + "/e1",
            Map.of(TYPE, "sub:Extraction", "sling:resourceType", "sub/Extraction",
                "extractedAnswer", "reduce readmissions", "confidence", 0.6));
    }

    /** A tree nothing may be written to, standing in for one the caller has no write access to. */
    private static final class ReadOnly extends ResourceWrapper
    {
        ReadOnly(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public ResourceResolver getResourceResolver()
        {
            final ResourceResolver wrapped = super.getResourceResolver();
            return new ResourceResolverWrapper(wrapped)
            {
                @Override
                public Resource getResource(final String path)
                {
                    final Resource found = wrapped.getResource(path);
                    return found == null ? null : new ReadOnly(found);
                }
            };
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
        }
    }

    private WorkflowTaskContext createTask(final Map<String, Object> payload)
    {
        return TaskContexts.of(this.submission, payload, Map.of());
    }

    private ValueMap getRecorded()
    {
        return this.context.resourceResolver().getResource(this.extraction.getPath()).getValueMap();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("reviewExtraction", this.handler.getName());
    }

    // The passage being wrong is a report about the extraction, not a verdict on the answer
    @Test
    void recordsARejectedPassageWithoutSettlingTheAnswer() throws Exception
    {
        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
            ReviewExtractionHandler.EVIDENCE_REJECTED, true)));

        assertTrue(getRecorded().get("evidenceRejected", Boolean.FALSE));
        assertNull(getRecorded().get("editDistance", Long.class), "the answer is still theirs to settle");
        assertEquals(0.6, getRecorded().get("confidence", Double.class), "and the model's own number stands");
    }

    @Test
    void readsAVerdictPostedAsAString() throws Exception
    {
        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
            ReviewExtractionHandler.EVIDENCE_REJECTED, "true")));

        assertTrue(getRecorded().get("evidenceRejected", Boolean.FALSE));
    }

    @Test
    void letsThemTakeThatBack() throws Exception
    {
        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
            ReviewExtractionHandler.EVIDENCE_REJECTED, true)));
        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
            ReviewExtractionHandler.EVIDENCE_REJECTED, false)));

        assertFalse(getRecorded().get("evidenceRejected", Boolean.FALSE));
    }

    @Test
    void leavesTheExtractionAloneWhenNoVerdictWasGiven() throws Exception
    {
        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS)));

        assertNull(getRecorded().get("evidenceRejected", Boolean.class));
        assertEquals(0.6, getRecorded().get("confidence", Double.class));
    }

    // Only the submitter's word on their own request counts
    @Test
    void refusesAReviewFromAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(
            createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
                ReviewExtractionHandler.EVIDENCE_REJECTED, true))));
        assertFalse(getRecorded().get("evidenceRejected", Boolean.FALSE));
    }

    @Test
    void refusesAReviewOfARequestAlreadySent()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("tags", new String[0]);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(
            createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
                ReviewExtractionHandler.EVIDENCE_REJECTED, true))));
        assertFalse(getRecorded().get("evidenceRejected", Boolean.FALSE));
    }

    @Test
    void refusesAReviewThatNamesNoQuestion()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(createTask(Map.of())));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, "  "))));
    }

    @Test
    void refusesAReviewOfSomethingNothingWasExtractedFor()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(
            createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, "study/title",
                ReviewExtractionHandler.EVIDENCE_REJECTED, true))));
    }

    @Test
    void refusesAReviewOnAnAnswerItMayNotWriteTo()
    {
        final WorkflowTaskContext readOnly = TaskContexts.of(
            new ReadOnly(this.submission),
            Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS, ReviewExtractionHandler.EVIDENCE_REJECTED, true),
            Map.of());

        assertThrows(PersistenceException.class, () -> this.handler.execute(readOnly));
    }

    // The verdict lands on the newest run, the one the form shows, even when an older run was surer
    @Test
    void reviewsTheNewestExtraction() throws Exception
    {
        final Resource newest = this.context.create().resource(this.extraction.getParent().getPath() + "/e2",
            Map.of(TYPE, "sub:Extraction", "sling:resourceType", "sub/Extraction",
                "extractedAnswer", "reduce readmissions", "confidence", 0.3));

        this.handler.execute(createTask(Map.of(ReviewExtractionHandler.QUESTION_PARAMETER, AIMS,
            ReviewExtractionHandler.EVIDENCE_REJECTED, true)));

        assertTrue(this.context.resourceResolver().getResource(newest.getPath()).getValueMap()
            .get("evidenceRejected", Boolean.FALSE));
        assertFalse(getRecorded().get("evidenceRejected", Boolean.FALSE), "the older run is left as it was");
    }
}
