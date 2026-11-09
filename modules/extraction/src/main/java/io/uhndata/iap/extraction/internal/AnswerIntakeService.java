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
 * whole document. A call whose reply cannot be read twice, or that cannot reach the model, loses only its own
 * questions; they stay unanswered and are asked again by the next reading.</p>
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

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * The result of reading a document.
     *
     * @param fields one result per question whose call went through, keyed by the question's key, in the order asked
     * @param degraded {@code true} when nothing was read and every question is left unanswered
     * @param reason what to tell the submitter when nothing was read, or {@code null} when the replies could not be
     *     read
     * @param incomplete {@code true} when some calls went through and others did not
     * @version $Id$
     * @since 0.1.0
     */
    public record IntakeResult(Map<String, FieldResult> fields, boolean degraded, String reason, boolean incomplete)
    {
        /** What the submitter is told when the model's replies could not be read. */
        static final String UNREADABLE_MESSAGE = "The model's answer could not be read";

        /** What the submitter is told when the model could not be reached. */
        static final String UNREACHABLE_MESSAGE = "The model could not be reached";

        /** What the submitter is told when the parsed text could not be read from the repository. */
        static final String TEXT_UNREADABLE = "The document's text could not be read";

        /** What the submitter is told when only some questions could be read. */
        static final String INCOMPLETE_MESSAGE = "Some questions could not be read. Read again to try them again";

        /**
         * Copies the fields, so the result cannot be changed later.
         *
         * @param fields one result per question
         * @param degraded {@code true} when nothing was read
         * @param reason what to tell the submitter, or {@code null}
         * @param incomplete {@code true} when only some calls went through
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
            return new IntakeResult(fields, false, null, false);
        }

        /**
         * The result when no reply could be read on either try.
         *
         * @return a degraded result with no fields and no reason
         */
        static IntakeResult createNothingRead()
        {
            return new IntakeResult(Map.of(), true, null, false);
        }

        /**
         * The result when the model was not asked, or could not be reached.
         *
         * @param reason what to tell the submitter
         * @return a degraded result with no fields and this reason
         */
        static IntakeResult createNotAsked(final String reason)
        {
            return new IntakeResult(Map.of(), true, reason, false);
        }

        /**
         * What to tell the submitter when nothing was read.
         *
         * @return the reason, or {@link #UNREADABLE_MESSAGE} when there is no reason
         */
        String getMessage()
        {
            return this.reason == null ? UNREADABLE_MESSAGE : this.reason;
        }
    }

    /**
     * Asks the model every question of the groups about a document.
     *
     * @param file the parsed file, used to name it in the logs
     * @param document the parsed text, which quotes are checked against
     * @param groups the questions, grouped as they should be asked, such as one group per requirement
     * @param references reference text for these questions, sent after the document, or {@code null} for none
     * @return one result per question read; a degraded result with a reason when nothing was read; an empty, not
     *     degraded result when there is nothing to ask
     */
    public IntakeResult run(final File file, final DocumentScan document, final List<ExtractionPrompt.Group> groups,
        final String references)
    {
        if (groups.stream().allMatch(group -> group.fields().isEmpty())) {
            return IntakeResult.createRead(Map.of());
        }
        if (document.isBlank()) {
            LOGGER.warn("There is nothing to read for {}", file.getPath());
            return IntakeResult.createNotAsked("The document has no text that can be read");
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
            return IntakeResult.createNotAsked("The questions leave no room to show the model the document");
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
            ErrorLogger.logError(e, ErrorContext.of(AnswerIntakeService.class, "askModel").about(file.getPath()));
            return IntakeResult.createNotAsked(IntakeResult.UNREACHABLE_MESSAGE);
        }
        return summarize(file, DocumentReader.read(client, plan, document));
    }

    /** Turns how each call went into the result, and reports each call the model could not take. */
    private static IntakeResult summarize(final File file, final DocumentReader.Reading reading)
    {
        boolean answered = false;
        boolean unreachable = false;
        for (final DocumentReader.Outcome outcome : reading.outcomes()) {
            answered |= outcome.status() == DocumentReader.Status.ANSWERED;
            if (outcome.status() == DocumentReader.Status.FAILED) {
                unreachable = true;
                // An outage of the model, which an administrator has to see to fix
                ErrorLogger.logProblem("The model could not be reached: " + outcome.problem(),
                    ErrorContext.of(AnswerIntakeService.class, "askModel").about(file.getPath()));
            }
        }
        if (!answered) {
            LOGGER.warn("No call about {} went through", file.getPath());
            return unreachable ? IntakeResult.createNotAsked(IntakeResult.UNREACHABLE_MESSAGE)
                : IntakeResult.createNothingRead();
        }
        final boolean incomplete = reading.outcomes().stream()
            .anyMatch(outcome -> outcome.status() != DocumentReader.Status.ANSWERED);
        return new IntakeResult(reading.answers(), false, null, incomplete);
    }
}
