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

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that saves what the model picked as a document's category: one run under the answer to the
 * classification's question. The answer's value stays empty: the submitter confirms the category, or picks one when
 * the run found none.
 *
 * <p>Only the job that holds the reading may save it: after a Stop or a Read again the reading is someone else's,
 * and a late pick must not land on it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordCategoryHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordCategory";

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordCategoryHandler.class);

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
        // A pick the model was unsure of comes as not found
        if (!(event.get(ReadingEvents.RESULT_KEY) instanceof final FieldResult result)) {
            throw new InvalidPayloadException("The event does not carry what the model picked");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final Question question = getQuestion(resolver, Payloads.requireText(event, ReadingEvents.REQUIREMENT_KEY,
            "The event does not name the classification"));
        final File file = ReadingEvents.getFile(resolver, Payloads.requireText(event, ReadingEvents.FILE_KEY,
            "The event does not name the document that was read"));
        ExtractionStatus.checkOutForJob(target, jobId);
        final Submission submission = SubmissionFiles.getSubmission(target);
        final String key = ExtractionField.getKey(submission.getSchemaVersion().getPath(), question);
        ExtractionFields.write(resolver, target, submission, Map.of(key, question), Map.of(key, result),
            List.of(file));
        LOGGER.info("Category recorded: submission={} question={} found={}", target.getPath(), question.getPath(),
            result.isFound());
    }

    /** The question of the classification the event names. */
    private static Question getQuestion(final ResourceResolver resolver, final String path)
        throws InvalidPayloadException
    {
        final Resource requirement = resolver.getResource(path);
        if (requirement == null || !requirement.isResourceType(ClassificationRequirement.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("The event names no classification requirement at " + path);
        }
        final ClassificationRequirement classification = requirement.adaptTo(ClassificationRequirement.class);
        final Question question = classification == null ? null : classification.getQuestion();
        if (question == null) {
            throw new InvalidPayloadException("The classification at " + path + " holds no question");
        }
        return question;
    }
}
