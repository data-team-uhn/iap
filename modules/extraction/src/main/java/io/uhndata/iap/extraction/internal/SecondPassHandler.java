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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code secondPass}: ask again for the fields the first pass left open.
 *
 * <p>A field is open when nothing answered it, or when its answer is under the confidence floor, or when a
 * quote behind it could not be found in the text. All three are read back off the submission, so this does
 * not depend on the first pass handing anything over.
 *
 * <p>Skips itself when the gate turned the document away, and when nothing is open.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SecondPassHandler implements ServiceTaskHandler
{
    /** The name a workflow definition calls this by. */
    public static final String NAME = "secondPass";

    private static final Logger LOGGER = LoggerFactory.getLogger(SecondPassHandler.class);

    @Reference
    private SecondPassService secondPass;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        if (!GateProposalHandler.passed(context)) {
            return;
        }
        final File file = GateProposalHandler.gatedFile(context);
        final Resource fileResource =
            file == null ? null : context.getResourceResolver().getResource(file.getPath());
        if (fileResource == null) {
            LOGGER.warn("The document the gate read is gone; not asking again");
            return;
        }
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.submission(target);
        final Map<String, Question> open = open(submission);
        if (open.isEmpty()) {
            return;
        }
        final List<ExtractionField> fields = new ArrayList<>();
        open.forEach((key, question) -> fields.add(ExtractionField.of(question, key)));

        final Map<String, FieldResult> found;
        try {
            found = this.secondPass.run(fileResource, file, fields);
        } catch (final IOException e) {
            // Never fails the run. The first pass's answers stand, and the submitter fills in the rest.
            LOGGER.warn("Could not ask again for {}: {}", target.getPath(), e.getMessage());
            return;
        }
        record(context, target, open, found, file);
    }

    private static void record(final WorkflowTaskContext context, final Resource target,
        final Map<String, Question> open, final Map<String, FieldResult> found, final File file)
        throws PersistenceException
    {
        for (final Map.Entry<String, FieldResult> entry : found.entrySet()) {
            final Question question = open.get(entry.getKey());
            if (entry.getValue().found() && question != null) {
                ExtractedAnswers.write(context.getResourceResolver(), target, question, entry.getValue(), file);
            }
        }
    }

    /**
     * The questions Step 2 should ask, by their path within the schema version. A question the submitter has
     * answered themselves is left alone: what a person said stands over what a model read.
     */
    private static Map<String, Question> open(final Submission submission)
    {
        final Set<String> settled = settled(submission);
        final String versionPath = submission.getSchemaVersion().getPath();
        final Map<String, Question> open = new HashMap<>();
        for (final Question question : submission.getQuestions()) {
            if (ExtractionField.isExtractable(question) && !settled.contains(question.getPath())) {
                open.put(question.getPath().substring(versionPath.length() + 1), question);
            }
        }
        return open;
    }

    /** Questions already answered well enough to leave alone. */
    private static Set<String> settled(final Submission submission)
    {
        final Set<String> settled = new HashSet<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question != null && !needsAnotherLook(answer)) {
                settled.add(question.getPath());
            }
        }
        return settled;
    }

    /**
     * Whether an answer is worth asking again. An answer with no extraction under it came from the
     * submitter, so it is settled whatever it says.
     */
    private static boolean needsAnotherLook(final Answer answer)
    {
        final List<Extraction> extractions = answer.getChildren(Extraction.class);
        if (extractions.isEmpty()) {
            return false;
        }
        for (final Extraction extraction : extractions) {
            final Double confidence = extraction.getConfidence();
            if (!extraction.isNeedsSecondLook()
                && confidence != null && confidence >= AnswerIntakeService.PENDING_CONFIDENCE) {
                return false;
            }
        }
        return true;
    }
}
