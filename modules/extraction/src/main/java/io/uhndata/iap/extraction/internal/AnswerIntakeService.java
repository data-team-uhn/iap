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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.submissions.models.File;

/**
 * Asks the model for every field in one call and checks its answers against the document. A long document is
 * cut to its start and its end to fit the call, but quotes are still checked against the whole document.
 *
 * <p>Each quote must really be in the document. A field's confidence goes down when its quotes are not found.
 * A field with no value is not found, whatever the model said. If the answer cannot be read on both tries,
 * every field is left unanswered and the result is marked degraded.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerIntakeService.class)
public class AnswerIntakeService
{
    /**
     * What confidence is multiplied by when none of a field's quotes are found, so 0.9 becomes 0.45. Not 0,
     * because the answer can still be right.
     */
    static final double UNVERIFIED_PENALTY = 0.5;

    /**
     * The most output tokens one answer may use, however many fields are asked. A provider refuses a request for
     * more than its model can write, and 16k is a common limit for hosted models.
     */
    static final long MAX_OUTPUT_TOKENS = 16_384L;

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerIntakeService.class);

    private static final String SCHEMA_NAME = "iap_intake";

    /** Output tokens for the parts of the answer that are not about one field. */
    private static final long BASE_TOKENS = 500L;

    /** Output tokens for one field's value, reasoning and quotes. */
    private static final long PER_FIELD_TOKENS = 400L;

    /** The name of this step in the logs and in the budget warning. */
    private static final String STAGE_NAME = "intake";

    private static final String RETRY_OPENING = "Your previous answer could not be read: ";

    private static final String RETRY_CLOSING =
        ". Answer again with exactly one JSON object matching the schema and nothing else.";

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * The result of one intake call.
     *
     * @param fields one result per field asked for, keyed by field name, in the order asked
     * @param degraded {@code true} when nothing was read and every field is left unanswered
     * @param reason what to tell the submitter when nothing was read, or {@code null} when the model's answer could
     *            not be read
     * @version $Id$
     * @since 0.1.0
     */
    public record IntakeResult(Map<String, FieldResult> fields, boolean degraded, String reason)
    {
        /** What the submitter is told when the model's answer could not be read. */
        static final String UNREADABLE_MESSAGE = "The model's answer could not be read";

        /** What the submitter is told when the model could not be reached. */
        static final String UNREACHABLE_MESSAGE = "The model could not be reached";

        /** What the submitter is told when the parsed text could not be read from the repository. */
        static final String TEXT_UNREADABLE = "The document's text could not be read";

        /**
         * A result with no reason.
         *
         * @param fields one result per field
         * @param degraded {@code true} when nothing was read
         */
        public IntakeResult(final Map<String, FieldResult> fields, final boolean degraded)
        {
            this(fields, degraded, null);
        }

        /**
         * Copies the fields, so the result cannot be changed later.
         *
         * @param fields one result per field
         * @param degraded {@code true} when nothing was read
         * @param reason what to tell the submitter, or {@code null}
         */
        public IntakeResult
        {
            // Not Map.copyOf, which loses the order: callers list the fields in the order they were asked
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * The result when the model's answer could not be read on either try.
         *
         * @return a degraded result with no fields and no reason
         */
        static IntakeResult createNothingRead()
        {
            return new IntakeResult(Map.of(), true);
        }

        /**
         * The result when the model was not asked, or could not be reached.
         *
         * @param reason what to tell the submitter
         * @return a degraded result with no fields and this reason
         */
        static IntakeResult createNotAsked(final String reason)
        {
            return new IntakeResult(Map.of(), true, reason);
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
     * Asks the model for every field in the document.
     *
     * @param file the parsed file, used to name it in the logs
     * @param document the parsed text, which quotes are checked against
     * @param fields the fields to extract
     * @return one result per field, or a degraded result with a reason when nothing was read; an empty, not
     *         degraded result when there are no fields
     * @throws IOException if the model cannot be reached
     */
    public IntakeResult run(final File file, final DocumentScan document, final List<ExtractionField> fields)
        throws IOException
    {
        return run(file, document, fields, null);
    }

    /**
     * Asks the model for every field in the document, with extra system instructions for this step.
     *
     * @param file the parsed file, used to name it in the logs
     * @param document the parsed text, which quotes are checked against
     * @param fields the fields to extract
     * @param extraSystem extra instructions put before the intake prompt; ignored when {@code null} or blank
     * @return one result per field, or a degraded result with a reason when nothing was read; an empty, not
     *         degraded result when there are no fields
     * @throws IOException if the model cannot be reached
     */
    public IntakeResult run(final File file, final DocumentScan document, final List<ExtractionField> fields,
        final String extraSystem) throws IOException
    {
        if (fields.isEmpty()) {
            return new IntakeResult(Map.of(), false);
        }
        if (document.isBlank()) {
            LOGGER.warn("There is nothing to read for {}", file.getPath());
            return IntakeResult.createNotAsked("The document has no text that can be read");
        }
        final String system = buildSystemPrompt(extraSystem);
        final String questions = IntakePayload.buildQuestionBlock(fields);
        final long maxOutputTokens = Math.min(BASE_TOKENS + PER_FIELD_TOKENS * fields.size(), MAX_OUTPUT_TOKENS);
        final long promptTokens = CallBudget.estimateTokens(system) + CallBudget.estimateTokens(questions);
        final String text = DocumentBudget.fitDocument(this.configurationService, STAGE_NAME, file, document.getText(),
            promptTokens, maxOutputTokens);
        if (text.isEmpty()) {
            // Without the document the model could only make answers up, so it is not asked
            LOGGER.warn("No room was left to show the intake any of {}", file.getPath());
            // The model's context window is too small for the questions, which only an administrator can fix
            ErrorLogger.logProblem("The questions leave no room for the document in the model's context window",
                ErrorContext.of(AnswerIntakeService.class, "intake").about(file.getPath()));
            return IntakeResult.createNotAsked("The questions leave no room to show the model the document");
        }
        final JsonObject answer = ask(IntakePayload.build(questions, text), fields, system, maxOutputTokens);
        if (answer == null) {
            LOGGER.warn("The intake did not answer in the required shape twice for {}", file.getPath());
            return IntakeResult.createNothingRead();
        }
        // Quotes are checked against the whole document, not only the part sent. A quote is real if it is
        // anywhere in the file the submitter attached.
        return new IntakeResult(readFields(answer, fields, document), false);
    }

    /**
     * Asks the model, and asks once more if the first answer cannot be read.
     *
     * @return the answer, or {@code null} when neither answer could be read
     * @throws IOException if the model cannot be reached
     */
    private JsonObject ask(final IntakePayload payload, final List<ExtractionField> fields, final String system,
        final long maxOutputTokens) throws IOException
    {
        final LLMRequestOptions options = LLMRequestOptions.builder()
            .maxOutputTokens(maxOutputTokens)
            .jsonSchema(SCHEMA_NAME, IntakePayload.buildResponseSchema(fields))
            .build();
        return ModelCall.askWithCorrection(this.llmClientFactory.getActiveClient(), system,
            payload.getUserMessage(), options, ModelReplies::readJsonObject,
            reply -> describeFault(reply, fields), STAGE_NAME);
    }

    /**
     * The system prompt: the step's extra instructions first, if there are any, then the intake prompt. The extra
     * instructions live on the step so they are only sent for the schema that needs them.
     */
    static String buildSystemPrompt(final String extraSystem)
    {
        final String intake = Prompts.read(Prompts.INTAKE_SYSTEM);
        return extraSystem == null || extraSystem.isBlank() ? intake : extraSystem.strip() + "\n\n" + intake;
    }

    /** The note sent with the second try: what was wrong with the reply, so the model can fix it. */
    static String describeFault(final String reply, final List<ExtractionField> fields)
    {
        return RETRY_OPENING + describeWhatWasWrong(reply, fields) + RETRY_CLOSING;
    }

    private static String describeWhatWasWrong(final String reply, final List<ExtractionField> fields)
    {
        if (reply == null || reply.isBlank()) {
            return "it was empty";
        }
        if (!reply.contains("{")) {
            return "it contained no JSON object";
        }
        if (reply.chars().filter(c -> c == '{').count() > reply.chars().filter(c -> c == '}').count()) {
            return "the JSON object was never closed, so the answer was cut off before it finished";
        }
        return "it was not a single JSON object with a key for each of " + fields.size() + " fields";
    }

    /**
     * Reads the model's answer for each field and checks its quotes.
     *
     * @param answer the model's answer, with one entry per field
     * @param fields the fields that were asked
     * @param document the document quotes are checked against
     * @return one result per field, in the order asked; a field missing from the answer is not found
     */
    static Map<String, FieldResult> readFields(final JsonObject answer, final List<ExtractionField> fields,
        final DocumentScan document)
    {
        final Map<String, FieldResult> results = new LinkedHashMap<>();
        for (final ExtractionField field : fields) {
            final JsonObject read = ModelReplies.readObject(answer, field.name());
            results.put(field.name(), read == null
                ? new FieldResult(field.name(), false, 0.0, null, "", List.of())
                : readField(field.name(), read, document));
        }
        return results;
    }

    /**
     * Reads one field's answer. No value means not found, and a found value loses confidence for each of its
     * quotes that is not in the document.
     */
    private static FieldResult readField(final String name, final JsonObject read, final DocumentScan document)
    {
        final String value = ModelReplies.readString(read, "value");
        // A blank value counts as not found, or it would be stored as an answer and block the question for good
        final boolean found = read.getBoolean("found_answer", false) && value != null && !value.isBlank();
        final List<FieldResult.Passage> evidence = readPassages(read);
        // The top of the fraction below: the quotes really found in the document
        final List<FieldResult.Passage> confirmedEvidence = verify(evidence, document);
        // The bottom: the different quotes offered. The same quote twice is one piece of evidence.
        final long distinct = evidence.stream().map(passage -> QuoteVerifier.normalize(passage.quote())).distinct()
            .count();
        // 2 of 2 quotes found keeps the confidence, 1 of 2 takes off a quarter, 0 of 2 halves it
        final double claimed = ModelReplies.readConfidence(read, "confidence");
        final double confidence = found ? claimed * getEvidenceFactor(confirmedEvidence.size(), distinct) : claimed;
        final String reasoning = ModelReplies.readString(read, "reasoning");
        return new FieldResult(name, found, confidence, found ? value : null, reasoning == null ? "" : reasoning,
            confirmedEvidence);
    }

    /**
     * The quotes that are really in the document, each with the heading above it and the document it is in. A
     * repeated quote is kept once.
     *
     * <p>A quote that is not found is dropped. A reviewer would otherwise see a sentence that is not in their
     * document.
     */
    private static List<FieldResult.Passage> verify(final List<FieldResult.Passage> offered,
        final DocumentScan document)
    {
        final List<FieldResult.Passage> kept = new ArrayList<>(offered.size());
        final Set<String> seen = new HashSet<>();
        for (final FieldResult.Passage passage : offered) {
            if (!seen.add(QuoteVerifier.normalize(passage.quote()))) {
                continue;
            }
            final int at = document.locate(passage.quote());
            if (at >= 0) {
                kept.add(passage.placeUnder(document.getHeading(at)).placeIn(document.getPart(at)));
            }
        }
        return kept;
    }

    /**
     * What a found answer's confidence is multiplied by: {@link #UNVERIFIED_PENALTY} when no quote was found, up
     * to 1 when all were. An answer with no quotes at all gets {@link #UNVERIFIED_PENALTY} too.
     *
     * @param kept how many quotes were found in the document
     * @param offered how many different quotes the model gave
     */
    private static double getEvidenceFactor(final int kept, final long offered)
    {
        if (offered == 0) {
            return UNVERIFIED_PENALTY;
        }
        return UNVERIFIED_PENALTY + (1.0 - UNVERIFIED_PENALTY) * ((double) kept / offered);
    }

    /**
     * The quotes the model gave for one field. Items that are not objects, and blank quotes, are skipped.
     *
     * @param read the model's answer for one field
     * @return the quotes, empty when there is no {@code evidence} list
     */
    private static List<FieldResult.Passage> readPassages(final JsonObject read)
    {
        final List<FieldResult.Passage> passages = new ArrayList<>();
        if (!read.containsKey("evidence") || read.get("evidence").getValueType() != JsonValue.ValueType.ARRAY) {
            return passages;
        }
        for (final JsonValue value : read.getJsonArray("evidence")) {
            if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject item = value.asJsonObject();
            final String quote = ModelReplies.readString(item, "quote");
            if (quote != null && !quote.isBlank()) {
                passages.add(new FieldResult.Passage(quote, ModelReplies.readLong(item, "page")));
            }
        }
        return passages;
    }
}
