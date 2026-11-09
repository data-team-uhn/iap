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
 * Service task {@code reviewExtraction}: records what the submitter said about the quotes behind a suggested
 * answer.
 *
 * <p>{@code evidenceRejected} means the quotes do not support the suggestion. It is a note about the run, not
 * about the answer, so it settles nothing and blocks nothing. Accepting or changing a suggestion is not a review:
 * that is saving the answer, which also measures how far the answer moved from the suggestion.
 *
 * <p>The payload names the question by its path within the schema version, the same way the save endpoint does,
 * so a form posts back what it was given.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReviewExtractionHandler implements ServiceTaskHandler
{
    /** The name a workflow definition calls this by. */
    public static final String HANDLER_NAME = "reviewExtraction";

    static final String QUESTION_PARAMETER = "question";

    static final String EVIDENCE_REJECTED = "evidenceRejected";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewExtractionHandler.class);

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Map<String, Object> payload = context.getEvent().getPayload();
        final String path = Objects.toString(payload.get(QUESTION_PARAMETER), "");
        if (path.isBlank()) {
            throw new InvalidPayloadException("A review has to name the question it is about");
        }
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.getSubmission(target);
        // Only the submitter may review, and only while the request is a draft
        SubmitterAccess.checkMayChange(submission, context.getActor());
        final Extraction extraction = findExtraction(submission, path);
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
        if (payload.containsKey(EVIDENCE_REJECTED)) {
            properties.put(EVIDENCE_REJECTED, isTrue(payload.get(EVIDENCE_REJECTED)));
        }
    }

    /** Reads a payload flag: a form posts the string "true", a test posts a boolean. */
    private static boolean isTrue(final Object value)
    {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(Objects.toString(value, ""));
    }

    /**
     * The newest run of the question, the one the form shows, or {@code null} when there is none or it found
     * nothing. It uses the same rule as the form ({@code Answer.getLatestExtraction}), so the review lands on the
     * run that was on screen.
     */
    private static Extraction findExtraction(final Submission submission, final String path)
    {
        final String wanted = submission.getSchemaVersion().getPath() + "/" + path;
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question == null || !wanted.equals(question.getPath())) {
                continue;
            }
            // A run that found nothing has no evidence to reject
            final Extraction latest = answer.getLatestExtraction();
            if (latest != null && !latest.getExtractedValues().isEmpty()) {
                return latest;
            }
        }
        return null;
    }
}
