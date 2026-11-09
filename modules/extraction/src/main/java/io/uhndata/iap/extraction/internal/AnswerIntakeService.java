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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.submissions.models.File;

/**
 * Asks the model a document's questions and checks its answers against the document. The calls are planned by
 * {@link ExtractionPrompt} and made by {@link DocumentReader}.
 *
 * <p>A long document is cut to its start and its end to fit the calls, but quotes are still checked against the
 * whole document. A call whose reply cannot be read twice loses only its own questions, and a model that cannot be
 * reached loses the questions of every call left. Lost questions get no run, so the next reading asks them again.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerIntakeService.class)
public class AnswerIntakeService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerIntakeService.class);

    /** The name of this step in the logs and in the budget warning. */
    private static final String STAGE_NAME = "extraction";

    /** The operation a failure to reach the model is recorded under. */
    private static final String ASK_MODEL_OPERATION = "askModel";

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * How much of a document's questions a reading read.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public enum Status
    {
        /** Every question was read. */
        READ,
        /** Some calls went through and others did not; the questions of the others are left unanswered. */
        INCOMPLETE,
        /** Nothing was read, and every question is left unanswered. */
        NOT_READ
    }

    /**
     * The result of reading a document.
     *
     * @param fields one result per question whose call went through, keyed by the question's key, in the order asked
     * @param status how much was read
     * @param message what to tell the submitter, or {@code null} when everything was read
     * @version $Id$
     * @since 0.1.0
     */
    public record IntakeResult(Map<String, FieldResult> fields, Status status, String message)
    {
        /** What the submitter is told when the model's replies could not be read. */
        static final String REPLY_UNREADABLE_MESSAGE = "The model's answer could not be read";

        /** What the submitter is told when the model could not be reached. */
        static final String UNREACHABLE_MESSAGE = "The model could not be reached";

        /** What the submitter is told when the parsed text could not be read from the repository. */
        static final String TEXT_UNREADABLE_MESSAGE = "The document's text could not be read";

        /** What the submitter is told when only some questions could be read. */
        static final String INCOMPLETE_MESSAGE = "Some questions could not be read. Read again to try them again";

        /**
         * Copies the fields, so the result cannot be changed later.
         *
         * @param fields one result per question
         * @param status how much was read
         * @param message what to tell the submitter, or {@code null}
         */
        public IntakeResult
        {
            // Not Map.copyOf, which loses the order the questions were asked in
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * The result of a reading that read everything it asked.
         *
         * @param fields one result per question
         * @return a complete result
         */
        static IntakeResult createRead(final Map<String, FieldResult> fields)
        {
            return new IntakeResult(fields, Status.READ, null);
        }

        /**
         * The result of a reading where only some calls went through.
         *
         * @param fields one result per question whose call went through
         * @return an incomplete result, telling the submitter to read again
         */
        static IntakeResult createIncomplete(final Map<String, FieldResult> fields)
        {
            return new IntakeResult(fields, Status.INCOMPLETE, INCOMPLETE_MESSAGE);
        }

        /**
         * The result when nothing was read.
         *
         * @param message what to tell the submitter
         * @return a result with no fields
         */
        static IntakeResult createNotRead(final String message)
        {
            return new IntakeResult(Map.of(), Status.NOT_READ, message);
        }
    }

    /**
     * Asks the model every question of the groups about a document.
     *
     * @param file the parsed file, used to name it in the logs
     * @param document the parsed text, which quotes are checked against
     * @param groups the questions, grouped as they should be asked, such as one group per requirement
     * @param references reference text for these questions, sent after the document, or {@code null} for none
     * @return one result per question read, and how much was read; an empty, read result when there is nothing to ask
     */
    public IntakeResult askQuestions(final File file, final DocumentScan document,
        final List<ExtractionPrompt.Group> groups, final String references)
    {
        if (groups.stream().allMatch(group -> group.fields().isEmpty())) {
            return IntakeResult.createRead(Map.of());
        }
        if (document.isBlank()) {
            LOGGER.warn("There is nothing to read for {}", file.getPath());
            return IntakeResult.createNotRead("The document has no text that can be read");
        }
        final ExtractionPrompt.Plan plan = ExtractionPrompt.plan(document.getText(), references, groups,
            (promptTokens, maxOutputTokens) -> CallBudget.calculateDocumentTokenBudget(this.configurationService,
                promptTokens, maxOutputTokens, STAGE_NAME));
        if (plan.calls().isEmpty()) {
            // Without the document the model could only make answers up, so it is not asked
            LOGGER.warn("No room was left to show the model any of {}", file.getPath());
            // The model's context window is too small for the questions, which only an administrator can fix
            ErrorLogger.logProblem("The questions leave no room for the document in the model's context window",
                ErrorContext.of(AnswerIntakeService.class, "plan").about(file.getPath()));
            return IntakeResult.createNotRead("The questions leave no room to show the model the document");
        }
        if (plan.document().wasCut()) {
            LOGGER.info("Document cut to fit: file={} documentChars={} sentChars={} omittedChars={}", file.getPath(),
                document.getText().length(), plan.document().text().length(), plan.document().omittedCharacters());
        }
        final LLMClient client;
        try {
            client = this.llmClientFactory.getActiveClient();
        } catch (final IOException e) {
            LOGGER.warn("No model can be asked about {}: {}", file.getPath(), e.getMessage());
            // No active model is configured, which an administrator has to fix
            ErrorLogger.logError(e, ErrorContext.of(AnswerIntakeService.class, ASK_MODEL_OPERATION)
                .about(file.getPath()));
            return IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE);
        }
        final DocumentReader.Reading reading = DocumentReader.read(client, plan, document);
        reportProblems(file, reading);
        return summarize(reading);
    }

    /** Reports, once per reading, an outage of the model and a model that does not follow the reply's schema. */
    private static void reportProblems(final File file, final DocumentReader.Reading reading)
    {
        final IOException failure = reading.findFailure();
        if (failure != null) {
            LOGGER.warn("The model could not be reached about {}: {}", file.getPath(), failure.getMessage());
            ErrorLogger.logError(failure, ErrorContext.of(AnswerIntakeService.class, ASK_MODEL_OPERATION)
                .about(file.getPath()));
        }
        final String problem = reading.findProblem();
        if (problem != null) {
            // Its replies were checked against the schema, so a model that cannot follow it is a setting to fix
            ErrorLogger.logProblem("The model did not answer in the required shape",
                ErrorContext.of(AnswerIntakeService.class, "readReply").about(file.getPath()).with("problem",
                    problem));
        }
    }

    /** Turns how each call went into the result. */
    private static IntakeResult summarize(final DocumentReader.Reading reading)
    {
        final List<DocumentReader.Outcome> outcomes = reading.outcomes();
        if (outcomes.stream().allMatch(outcome -> outcome.status() == DocumentReader.Status.ANSWERED)) {
            return IntakeResult.createRead(reading.answers());
        }
        if (outcomes.stream().anyMatch(outcome -> outcome.status() == DocumentReader.Status.ANSWERED)) {
            return IntakeResult.createIncomplete(reading.answers());
        }
        return IntakeResult.createNotRead(reading.findFailure() != null ? IntakeResult.UNREACHABLE_MESSAGE
            : IntakeResult.REPLY_UNREADABLE_MESSAGE);
    }
}
