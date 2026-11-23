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
import java.util.Objects;

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
 * reading ({@link EndReadingHandler}). When the model fails, unreachable or with no reply of a form readable, the
 * forms after are not asked: it is likely failing for them too. A form that fails for its own reason, such as a
 * document with no text, is left out and the next one is read. What was read is still saved, with a note on what
 * went wrong first. Only when nothing was read is the reading given up.</p>
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

    @Reference
    private FormsToRead formsToRead;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    @Override
    public String findReasonNotToStart(final Submission submission)
    {
        return this.formsToRead.findReasonNotToStart(submission);
    }

    @Override
    public void read(final Resource submission, final String jobId) throws ReadingEndedException, WorkflowException
    {
        final Submission model = SubmissionFiles.getSubmission(submission);
        final List<ExtractedForm> forms = new ArrayList<>();
        String note = null;
        String failure = null;
        for (final FormToRead reading : this.formsToRead.getFormsToRead(model)) {
            final IntakeResult result = this.reader.ask(model.getPath(), RequirementReader.Request.of(reading));
            if (result.status() == AnswerIntakeService.Status.NOT_READ) {
                failure = Objects.requireNonNullElse(failure, result.message());
            } else {
                forms.add(toExtractedForm(reading, result));
            }
            if (note == null) {
                note = result.describeShortfall();
            }
            if (result.isModelFailing()) {
                // Every form left would fail the same way, and each would still be asked and paid for
                break;
            }
        }
        if (forms.isEmpty() && failure != null) {
            this.jobs.giveUpOwnReading(submission, jobId, failure);
            return;
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
        final String note) throws ReadingEndedException, WorkflowException
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
