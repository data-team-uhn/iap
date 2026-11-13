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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.extraction.internal.FormsToRead.FormToRead;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * The background job that reads the answers out of a submission's documents once the submitter confirmed the
 * category: each form that names its documents, in schema order, with its open questions ({@link FormsToRead}),
 * asked through {@link RequirementReader}.
 *
 * <p>The job fires {@code extracted} with everything it read, and that system workflow saves it and ends the
 * reading ({@link EndReadingHandler}). When a call fails, the forms after it are not asked: the model is
 * likely down for them too. What was read before is still saved, with a note that Read again is worth it. Only
 * when nothing was read is the reading given up.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + MainExtractionJobConsumer.JOB_TOPIC })
public class MainExtractionJobConsumer implements JobConsumer, ReadingJob
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/main";

    /** The event that saves the answers. */
    static final String EXTRACTED_EVENT = "extracted";

    @Reference
    private ReadingJobs jobs;

    @Reference
    private RequirementReader reader;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    /**
     * Never: one job is queued per request, and one that finds nothing open still has to end the reading.
     *
     * @param submission the submission to read
     * @return {@code false}
     */
    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        return false;
    }

    @Override
    public String findReasonNotToStart(final Submission submission)
    {
        return FormsToRead.findReasonNotToStart(submission);
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final Submission model = SubmissionFiles.getSubmission(submission);
        final List<ExtractedForm> forms = new ArrayList<>();
        String note = null;
        for (final FormToRead reading : FormsToRead.getFormsToRead(model)) {
            final IntakeResult result = this.reader.ask(model.getPath(), RequirementReader.Request.of(reading));
            if (result.status() == AnswerIntakeService.Status.NOT_READ) {
                if (forms.isEmpty()) {
                    this.jobs.giveUp(submission, jobId, result.message(), true);
                    return;
                }
                note = IntakeResult.INCOMPLETE_MESSAGE;
                break;
            }
            forms.add(toExtractedForm(reading, result));
            if (result.status() == AnswerIntakeService.Status.INCOMPLETE) {
                note = IntakeResult.INCOMPLETE_MESSAGE;
            }
        }
        record(submission, jobId, forms, note);
    }

    private static ExtractedForm toExtractedForm(final FormToRead reading, final IntakeResult result)
    {
        final Map<String, String> questions = new LinkedHashMap<>();
        reading.questions().forEach((key, question) -> questions.put(key, question.getPath()));
        return new ExtractedForm(questions, result.fields(), reading.files().stream().map(File::getPath).toList());
    }

    /** Saves the answers and ends the reading through the {@code extracted} system workflow. */
    private void record(final Resource submission, final String jobId, final List<ExtractedForm> forms,
        final String note) throws WorkflowException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ReadingEvents.JOB_KEY, jobId);
        payload.put(EndReadingHandler.FORMS_KEY, forms);
        if (note != null) {
            payload.put(ReadingEvents.MESSAGE_KEY, note);
        }
        this.jobs.sendOutcome(submission, new WorkflowEvent(EXTRACTED_EVENT, payload));
    }
}
