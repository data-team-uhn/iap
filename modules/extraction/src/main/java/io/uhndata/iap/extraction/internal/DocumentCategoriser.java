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
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.OfferedOption;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Asks the model which category an uploaded document falls in, such as the kind of study a proposal describes,
 * and saves the pick as a suggestion on the category question. One step of {@link DecisionJobConsumer}.
 *
 * <p>The question's options are the categories, and the classification's prompt and template guide the model.
 * A categorisation must never guess: a pick less sure than the classification's threshold, or one naming no
 * category the question offers, is saved as not found, and the submitter picks. Either way the submitter is asked
 * to confirm the category before the reading goes on.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DocumentCategoriser.class)
public class DocumentCategoriser
{
    /** The event that saves the pick. */
    static final String CATEGORISED_EVENT = "categorised";

    @Reference
    private ReadingJobs jobs;

    @Reference
    private RequirementReader reader;

    /**
     * Asks one classification and saves the pick through the {@code categorised} system workflow.
     *
     * @param submission the submission being read, through the job's session
     * @param jobId the job's id, which the claim holds
     * @param classification the classification to ask; it holds a question
     * @param file the parsed upload it reads
     * @throws ReadingEndedException when the job must stop: the reading was given up, or is no longer this job's
     * @throws WorkflowException when the engine refuses the pick for a reason other than a lost claim
     */
    void categorise(final Resource submission, final String jobId, final ClassificationRequirement classification,
        final File file) throws ReadingEndedException, WorkflowException
    {
        final RequirementReader.Request request =
            RequirementReader.Request.ofClassification(SubmissionFiles.getSubmission(submission), classification, file);
        final String key = request.fields().get(0).key();
        final IntakeResult result = this.reader.ask(submission.getPath(), request);
        // A document too long to read gets no pick, as an unsure one, so the other checks still go on
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            this.jobs.giveUpOwnReading(submission, jobId, result.message());
            throw new ReadingEndedException();
        }
        final FieldResult pick = result.fields().getOrDefault(key, FieldResult.createNotFound(key));
        final FieldResult recorded = isSurePick(pick, classification) ? pick : FieldResult.createNotFound(key);
        this.jobs.sendOutcome(submission, new WorkflowEvent(CATEGORISED_EVENT, Map.of(
            ReadingEvents.JOB_KEY, jobId, ReadingEvents.REQUIREMENT_KEY, classification.getPath(),
            ReadingEvents.FILE_KEY, file.getPath(), ReadingEvents.RESULT_KEY, recorded)));
    }

    /**
     * Whether the model picked one of the classification's categories, at least as sure as the classification
     * asks. Not every provider holds the reply to the schema's list, and a pick outside it would match no condition.
     *
     * @param result what the model answered
     * @param classification the classification, for its categories and the confidence it needs
     * @return {@code true} when the pick may be offered as a suggestion
     */
    static boolean isSurePick(final FieldResult result, final ClassificationRequirement classification)
    {
        final Set<String> categories = classification.getCategories().stream()
            .map(OfferedOption::value)
            .collect(Collectors.toSet());
        return result.isFound() && result.confidence() >= classification.getConfidenceThreshold()
            && categories.containsAll(result.values());
    }
}
