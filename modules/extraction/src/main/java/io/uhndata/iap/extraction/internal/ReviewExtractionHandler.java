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
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code reviewExtraction}: record what the submitter said about a pre-filled answer.
 *
 * <p>Two verdicts, and they are independent. {@code confirmed} settles the answer: the submitter has vouched
 * for it, so it is marked reviewed and its confidence becomes 1. {@code evidenceRejected} says the quoted
 * passage does not support the answer. That is a report about the extraction, not about the answer, so it
 * settles nothing and blocks nothing.
 *
 * <p>The payload names the question by its path relative to the schema version, the same way the save
 * endpoint does, so a form posts back what it was given.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReviewExtractionHandler implements ServiceTaskHandler
{
    /** The name a workflow definition calls this by. */
    public static final String NAME = "reviewExtraction";

    static final String QUESTION = "question";

    static final String CONFIRMED = "confirmed";

    static final String EVIDENCE_REJECTED = "evidenceRejected";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewExtractionHandler.class);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Map<String, Object> payload = context.getEvent().getPayload();
        final String path = Objects.toString(payload.get(QUESTION), "");
        if (path.isBlank()) {
            throw new InvalidPayloadException("A review has to name the question it is about");
        }
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.submission(target);
        final Extraction extraction = extractionFor(submission, path);
        if (extraction == null) {
            throw new InvalidPayloadException("Nothing was extracted for " + path);
        }
        final Resource resource = context.getResourceResolver().getResource(extraction.getPath());
        final ModifiableValueMap properties =
            resource == null ? null : resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record a review on " + extraction.getPath());
        }
        apply(payload, properties);
        LOGGER.debug("Recorded a review of {} on {}", path, target.getPath());
    }

    private static void apply(final Map<String, Object> payload, final ModifiableValueMap properties)
    {
        if (isTrue(payload.get(CONFIRMED))) {
            // A person has vouched for it, so there is nothing left to be unsure about
            properties.put("reviewed", Boolean.TRUE);
            properties.put("confidence", 1.0);
            properties.put("needsSecondLook", Boolean.FALSE);
        }
        if (payload.containsKey(EVIDENCE_REJECTED)) {
            properties.put(EVIDENCE_REJECTED, isTrue(payload.get(EVIDENCE_REJECTED)));
        }
    }

    /** A form posts strings, a test posts booleans, and both mean the same thing. */
    private static boolean isTrue(final Object value)
    {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(Objects.toString(value, ""));
    }

    /** The surest extraction under the answer to the named question, or {@code null} when there is none. */
    private static Extraction extractionFor(final Submission submission, final String path)
    {
        final String wanted = submission.getSchemaVersion().getPath() + "/" + path;
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question == null || !wanted.equals(question.getPath())) {
                continue;
            }
            Extraction best = null;
            for (final Extraction extraction : answer.getChildren(Extraction.class)) {
                if (best == null || score(extraction) > score(best)) {
                    best = extraction;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static double score(final Extraction extraction)
    {
        final Double confidence = extraction.getConfidence();
        return confidence == null ? 0.0 : confidence;
    }
}
