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
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that ends a step of a reading: it saves the answers the event carries, if any, then marks the
 * reading {@code done} with the job's message for the submitter and releases the claim. Each answer is saved as a
 * run under its question's answer, found or not. The answers' values stay empty: each run is a suggestion until the
 * submitter accepts it.
 *
 * <p>Only the job that holds the reading may end it: after a Stop or a Read again the reading is someone else's,
 * and late answers must not land on it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class EndReadingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "endReading";

    /** The payload entry holding what was read, a list of {@link ExtractedForm}; absent when nothing was read. */
    static final String FORMS_KEY = "forms";

    private static final Logger LOGGER = LoggerFactory.getLogger(EndReadingHandler.class);

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
        final String jobId = ReadingEvents.getJobId(event);
        final List<ExtractedForm> forms = getForms(event);
        ExtractionStatus.checkOutForJob(target, jobId);
        final ResourceResolver resolver = context.getResourceResolver();
        final Submission submission = SubmissionFiles.getSubmission(target);
        for (final ExtractedForm form : forms) {
            ExtractionFields.write(resolver, target, submission, getQuestions(resolver, form),
                form.results(), getFiles(resolver, form));
        }
        final Object message = event.get(ReadingEvents.MESSAGE_KEY);
        ExtractionStatus.endReading(target, message instanceof final String text ? text : null);
        LOGGER.info("Reading step ended: submission={} forms={}", target.getPath(), forms.size());
    }

    private static List<ExtractedForm> getForms(final WorkflowEvent event) throws InvalidPayloadException
    {
        final Object value = event.get(FORMS_KEY);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof final List<?> list)) {
            throw new InvalidPayloadException("The event carries forms that are not a list");
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
            files.add(ReadingEvents.getFile(resolver, path));
        }
        return files;
    }
}
