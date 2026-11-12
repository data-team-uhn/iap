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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that saves what the main extraction read: one run under the answer of each question asked,
 * found or not, and the reading {@code done}. The answers' values stay empty: each run is a suggestion until the
 * submitter accepts it. When some calls did not go through, the reading ends with a note saying Read again is
 * worth it.
 *
 * <p>Only the job that holds the reading may save it: after a Stop or a Read again the reading is someone else's,
 * and late answers must not land on it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordExtractionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordExtraction";

    /** The payload entry holding what was read, a list of {@link ExtractedForm}. */
    static final String FORMS_KEY = "forms";

    /** The payload entry holding the note for the submitter; absent when every call went through. */
    static final String MESSAGE_KEY = "message";

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordExtractionHandler.class);

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final WorkflowEvent event = context.getEvent();
        final String jobId =
            Payloads.requireText(event, ClaimReadingHandler.JOB_KEY, "The event does not name the job");
        final List<ExtractedForm> forms = getForms(event);
        ExtractionStatus.checkOut(target);
        final ModifiableValueMap properties = target.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the answers of " + target.getPath());
        }
        if (!jobId.equals(properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class))) {
            throw new InvalidStateException("The reading of " + target.getPath() + " is no longer this job's");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final Submission submission = SubmissionFiles.getSubmission(target);
        for (final ExtractedForm form : forms) {
            ExtractionFields.write(resolver, target, submission, getQuestions(resolver, form),
                form.results(), getFiles(resolver, form));
        }
        ExtractionStatus.releaseClaim(properties);
        final Object message = event.get(MESSAGE_KEY);
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_DONE,
            message instanceof final String text ? text : null);
        LOGGER.info("Answers recorded: submission={} forms={}", target.getPath(), forms.size());
    }

    private static List<ExtractedForm> getForms(final WorkflowEvent event) throws InvalidPayloadException
    {
        if (!(event.get(FORMS_KEY) instanceof final List<?> list)) {
            throw new InvalidPayloadException("The event does not carry what was read");
        }
        final List<ExtractedForm> forms = new ArrayList<>();
        for (final Object item : list) {
            if (!(item instanceof final ExtractedForm form)) {
                throw new InvalidPayloadException("The event carries something that is not a form's answers");
            }
            forms.add(form);
        }
        return forms;
    }

    /** The questions asked, by key. A question that went away since is left out, with its answer. */
    private static Map<String, Question> getQuestions(final ResourceResolver resolver, final ExtractedForm form)
    {
        final Map<String, Question> questions = new LinkedHashMap<>();
        form.questions().forEach((key, path) -> {
            final Resource question = resolver.getResource(path);
            final Question model = question == null ? null : question.adaptTo(Question.class);
            if (model != null) {
                questions.put(key, model);
            }
        });
        return questions;
    }

    private static List<File> getFiles(final ResourceResolver resolver, final ExtractedForm form)
        throws InvalidPayloadException
    {
        final List<File> files = new ArrayList<>();
        for (final String path : form.files()) {
            final Resource file = resolver.getResource(path);
            if (file == null) {
                throw new InvalidPayloadException("The event names no document at " + path);
            }
            files.add(file.adaptTo(File.class));
        }
        return files;
    }
}
