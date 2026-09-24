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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.submissions.models.File;

/**
 * The extraction pass: one call that asks for every field at once, over the whole document.
 *
 * <p>Nothing is made up. Every quote is checked against the document, and a field's confidence is scaled by
 * how much of its evidence held up; a field with no value at all is reported as not found whatever the model
 * said. An answer that cannot be read twice over leaves every field unanswered, marked degraded, rather than
 * settling any of them by guesswork.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerIntakeService.class)
public class AnswerIntakeService
{
    /**
     * What we multiply confidence by when none of a field's quotes could be found. 0.9 becomes 0.45. Not 0,
     * because the answer can still be right.
     */
    static final double UNVERIFIED_PENALTY = 0.5;

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerIntakeService.class);

    private static final String SCHEMA_NAME = "iap_intake";

    /** Room for the envelope of the answer. */
    private static final long BASE_TOKENS = 500L;

    /** Room for one field's value, reasoning and quotes. */
    private static final long PER_FIELD_TOKENS = 400L;

    /** What is asking, in the logs and in the budget warning. */
    private static final String STAGE = "intake";

    private static final String OPENING = "Your previous answer could not be read: ";

    private static final String CLOSING =
        ". Answer again with exactly one JSON object matching the schema and nothing else.";

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * What the intake pass produced.
     *
     * @param fields one result per field asked for, by field name, in the order asked
     * @param degraded whether the model's answer could not be read, leaving every field unanswered
     * @version $Id$
     * @since 0.1.0
     */
    public record IntakeResult(Map<String, FieldResult> fields, boolean degraded)
    {
        /**
         * Takes copies, so a result cannot be changed once produced.
         *
         * @param fields one result per field
         * @param degraded whether the answer could not be read
         */
        public IntakeResult
        {
            // A copy that keeps the order, which Map.copyOf does not: the fields read back in the order the
            // schema asks them, and a caller listing them should not have to sort them again
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * The result when the model's answer could not be read twice over: nothing answered.
         *
         * @return a degraded result
         */
        static IntakeResult nothingRead()
        {
            return new IntakeResult(Map.of(), true);
        }
    }

    /**
     * Ask for every field over the document.
     *
     * @param file the parsed file, for what to call it in a warning
     * @param document its parsed text, prepared for the quotes that will be checked against it
     * @param fields what to extract
     * @return what the model read out, degraded when its answer could not be read
     * @throws IOException if the model cannot be reached
     */
    public IntakeResult run(final File file, final DocumentScan document, final List<ExtractionField> fields)
        throws IOException
    {
        return run(file, document, fields, null);
    }

    /**
     * Ask for every field over the document, with extra system instructions for this step only.
     *
     * @param file the parsed file, for what to call it in a warning
     * @param document its parsed text, prepared for the quotes that will be checked against it
     * @param fields what to extract
     * @param extraSystem domain knowledge for this step, prepended to the intake prompt; ignored when blank
     * @return what the model read out, degraded when its answer could not be read
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
            return IntakeResult.nothingRead();
        }
        final String system = systemPrompt(extraSystem);
        final String questions = IntakePayload.buildQuestionBlock(fields);
        final long maxOutputTokens = BASE_TOKENS + PER_FIELD_TOKENS * fields.size();
        final long promptTokens = CallBudget.estimateTokens(system) + CallBudget.estimateTokens(questions);
        final String text = DocumentBudget.fitDocument(this.configurationService, STAGE, file, document.getText(),
            promptTokens, maxOutputTokens);
        if (text.isEmpty()) {
            // Asking every field over no document at all is a call that can only invent, so it is not made
            LOGGER.warn("No room was left to show the intake any of {}", file.getPath());
            return IntakeResult.nothingRead();
        }
        final JsonObject answer = ask(IntakePayload.build(questions, text), fields, system, maxOutputTokens);
        if (answer == null) {
            LOGGER.warn("The intake did not answer in the required shape twice for {}", file.getPath());
            return IntakeResult.nothingRead();
        }
        // Checked against the whole document, not the part that fitted in the call. A quote is evidence when it
        // is in the document the submitter attached; where the token budget happened to fall is this pipeline
        // business and says nothing about whether the passage is real.
        return new IntakeResult(readFields(answer, fields, document), false);
    }

    /**
     * Put the question to the model, once, and once more if the first answer was not the shape it had to be.
     *
     * @return the answer, or {@code null} when neither could be read
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
            reply -> describeFault(reply, fields), STAGE);
    }

    /**
     * The system prompt: this step's extra instructions first, when it has any, then the intake's own. Domain
     * knowledge that is only worth sending for one schema sits on the step, not on every reading.
     */
    static String systemPrompt(final String extraSystem)
    {
        final String intake = Prompts.read(Prompts.INTAKE_SYSTEM);
        return extraSystem == null || extraSystem.isBlank() ? intake : extraSystem.strip() + "\n\n" + intake;
    }

    /** What was wrong with a reply, in terms a model can act on. */
    static String describeFault(final String reply, final List<ExtractionField> fields)
    {
        return OPENING + whatWasWrong(reply, fields) + CLOSING;
    }

    private static String whatWasWrong(final String reply, final List<ExtractionField> fields)
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
     * Read one answer per field, holding each to the evidence rules.
     *
     * @param answer the object holding one entry per field
     * @param fields what was asked
     * @param document the document quotes are checked against
     * @return one result per field, in the order they were asked
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
     * One field, held to the evidence rules: no value means not found, and a found value none of whose quotes
     * can be found in the text is discounted.
     */
    private static FieldResult readField(final String name, final JsonObject read, final DocumentScan document)
    {
        final String value = ModelReplies.readString(read, "value");
        final boolean found = read.getBoolean("found_answer", false) && value != null;
        final List<FieldResult.Passage> offered = readPassages(read);
        final List<FieldResult.Passage> kept = verify(offered, document);
        // Scaled by how many quotes checked out. 2 of 2 keeps it, 1 of 2 takes off a quarter, 0 of 2 halves it.
        final double claimed = ModelReplies.readConfidence(read, "confidence");
        final double confidence = found ? claimed * evidenceFactor(kept.size(), offered.size()) : claimed;
        final String reasoning = ModelReplies.readString(read, "reasoning");
        return new FieldResult(name, found, confidence, found ? value : null, reasoning == null ? "" : reasoning,
            kept);
    }

    /**
     * The quotes that are really in the document, each under the heading it sits below.
     *
     * <p>A quote nobody can find is dropped rather than stored. It is not evidence, and showing it to a
     * reviewer as though it were would be worse than showing none: they would read a sentence that is not in
     * their document. The answer still pays for it, through the confidence.
     */
    private static List<FieldResult.Passage> verify(final List<FieldResult.Passage> offered,
        final DocumentScan document)
    {
        final List<FieldResult.Passage> kept = new ArrayList<>(offered.size());
        for (final FieldResult.Passage passage : offered) {
            final int at = document.locate(passage.quote());
            if (at >= 0) {
                kept.add(passage.under(document.headingAt(at)).in(document.partAt(at)));
            }
        }
        return kept;
    }

    /**
     * The multiplier for a found answer, from {@link #UNVERIFIED_PENALTY} when no quote checked out to 1 when
     * all of them did. An answer with no quotes at all gets the same 0.5 as one whose quotes all failed.
     *
     * @param kept how many quotes were really in the text
     * @param offered how many the model gave
     */
    private static double evidenceFactor(final int kept, final int offered)
    {
        if (offered == 0) {
            return UNVERIFIED_PENALTY;
        }
        return UNVERIFIED_PENALTY + (1.0 - UNVERIFIED_PENALTY) * ((double) kept / offered);
    }

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
