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
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.llm.CallBudget;

/**
 * Turns a document and the questions asked of it into model calls.
 *
 * <p>Every call opens with the same bytes: the system prompt, then the document. Only the questions differ. A
 * provider caches the start of a prompt, so after the first call the document costs little. The questions can then
 * be asked in several small calls instead of one large one. A small call gives a short reply, and a reply that
 * cannot be read loses only its own questions.</p>
 *
 * <p>The document is fitted once, against the largest question block, so it is the same in every call.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionPrompt
{
    /** The most questions one call asks. A group with more is asked in several calls. */
    static final int MAX_FIELDS_PER_CALL = 10;

    /** The reply's room for its braces and for a model that writes more than it was asked. */
    static final long BASE_OUTPUT_TOKENS = 500;

    /** The reply's room for each question: its quotes, its reasoning and its value. */
    static final long OUTPUT_TOKENS_PER_FIELD = 400;

    private static final String SYSTEM_PROMPT = "/prompts/extraction_system.md";

    private static final String DOCUMENT_START = "<document>\n";

    private static final String DOCUMENT_END = "\n</document>";

    private static final Pattern DOCUMENT_END_TAG = Pattern.compile("</(document)>", Pattern.CASE_INSENSITIVE);

    private static final String BREAK = "\n\n";

    private ExtractionPrompt()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * Questions asked together, such as those of one requirement.
     *
     * @param name what the questions have in common, for the log
     * @param fields the questions, in the order they are asked
     * @version $Id$
     * @since 0.1.0
     */
    record Group(@NotNull String name, @NotNull List<ExtractionField> fields)
    {
    }

    /**
     * One model call.
     *
     * @param group the name of the group the questions come from
     * @param fields the questions this call asks
     * @param systemPrompt the system prompt
     * @param userMessage the document, then the questions
     * @param responseSchema the schema the reply must follow, see {@link ResponseSchema}
     * @param maxOutputTokens the room the reply is given
     * @version $Id$
     * @since 0.1.0
     */
    record Call(@NotNull String group, @NotNull List<ExtractionField> fields, @NotNull String systemPrompt,
        @NotNull String userMessage, @NotNull String responseSchema, long maxOutputTokens)
    {
    }

    /**
     * The calls that ask every question of a document.
     *
     * @param calls the calls, empty when there is nothing to ask or no room to show the document
     * @param document the document as every call carries it, and what had to be left out of it
     * @version $Id$
     * @since 0.1.0
     */
    record Plan(@NotNull List<Call> calls, @NotNull CallBudget.FittedText document)
    {
    }

    /**
     * Plans the calls that ask every question of a document.
     *
     * @param markdown the document's text
     * @param groups the questions, grouped as they should be asked
     * @param contextLimitTokens the model's context window, or 0 when it does not say
     * @return the plan
     * @throws IOException when the system prompt cannot be read out of the bundle
     */
    @NotNull
    static Plan plan(@NotNull final String markdown, @NotNull final List<Group> groups,
        final long contextLimitTokens) throws IOException
    {
        final List<Group> batches = split(groups);
        if (batches.isEmpty() || markdown.isBlank()) {
            return new Plan(List.of(), new CallBudget.FittedText("", false, 0));
        }
        final String systemPrompt = readSystemPrompt();
        final long largestQuestionBlock = batches.stream()
            .mapToLong(batch -> CallBudget.estimateTokens(buildQuestionBlock(batch.fields()))).max().orElse(0);
        final long largestReply = batches.stream()
            .mapToLong(batch -> calculateMaxOutputTokens(batch.fields())).max().orElse(0);
        final long promptTokens = CallBudget.estimateTokens(systemPrompt)
            + CallBudget.estimateTokens(DOCUMENT_START + DOCUMENT_END + BREAK) + largestQuestionBlock;
        final CallBudget.FittedText document = CallBudget.fitToTokens(markdown.strip(),
            CallBudget.calculateDocumentTokenBudget(contextLimitTokens, promptTokens, largestReply));
        if (document.text().isEmpty()) {
            return new Plan(List.of(), document);
        }
        final String documentBlock = buildDocumentBlock(document.text());
        final List<Call> calls = batches.stream()
            .map(batch -> new Call(batch.name(), batch.fields(), systemPrompt,
                documentBlock + BREAK + buildQuestionBlock(batch.fields()), ResponseSchema.build(batch.fields()),
                calculateMaxOutputTokens(batch.fields())))
            .toList();
        return new Plan(calls, document);
    }

    /**
     * The fixed system prompt every call opens with.
     *
     * @return the prompt
     * @throws IOException when it cannot be read out of the bundle
     */
    @NotNull
    static String readSystemPrompt() throws IOException
    {
        try (Reader prompt = new InputStreamReader(Objects.requireNonNull(
            ExtractionPrompt.class.getResourceAsStream(SYSTEM_PROMPT), "The system prompt is packaged with this class"),
            StandardCharsets.UTF_8)) {
            final StringWriter text = new StringWriter();
            prompt.transferTo(text);
            return text.toString().strip();
        }
    }

    /**
     * The document as a call carries it. A closing tag inside the text is broken up, so the document cannot end its
     * own block and pass what follows off as questions.
     *
     * @param text the document's text
     * @return the block
     */
    @NotNull
    static String buildDocumentBlock(@NotNull final String text)
    {
        return DOCUMENT_START + DOCUMENT_END_TAG.matcher(text).replaceAll("<\\\\/$1>") + DOCUMENT_END;
    }

    /**
     * The questions as a call asks them.
     *
     * @param fields the questions
     * @return the block, ending with the request to answer them all
     */
    @NotNull
    static String buildQuestionBlock(@NotNull final List<ExtractionField> fields)
    {
        final StringBuilder block = new StringBuilder("<questions>\n");
        for (final ExtractionField field : fields) {
            block.append("\n### ").append(field.key()).append('\n');
            appendLine(block, "Question", field.text());
            appendLine(block, "Details", field.description());
            appendLine(block, "Asked because", field.purpose());
            appendLine(block, "How to find it", field.instructions());
            appendLine(block, "Answer with", describeAnswer(field));
        }
        return block.append("\n</questions>\n\nAnswer every question above, in one JSON object keyed by the question"
            + " keys.").toString();
    }

    /**
     * What form a field's answer takes, in words: the half of the schema a model reads.
     *
     * @param field the field
     * @return the description, followed by the options when there are any
     */
    @NotNull
    static String describeAnswer(@NotNull final ExtractionField field)
    {
        if (!field.answer().options().isEmpty()) {
            final StringBuilder description = new StringBuilder(field.answer().multiple()
                ? "a list of the values below that apply" : "one of the values below");
            for (final ExtractionField.Option option : field.answer().options()) {
                description.append("\n- `").append(option.value()).append("`: ").append(option.label());
                if (option.description() != null) {
                    description.append(" (").append(option.description()).append(')');
                }
            }
            return description.toString();
        }
        final String one = describeOne(field);
        return field.answer().multiple() ? "a list, each item " + one : one;
    }

    private static String describeOne(final ExtractionField field)
    {
        return switch (field.answer().dataType()) {
            case ExtractionField.LONG -> "a whole number" + describeBounds(field);
            case ExtractionField.DOUBLE -> "a number" + describeBounds(field);
            case ExtractionField.BOOLEAN -> "true or false";
            case ExtractionField.DATE -> "a date, as YYYY-MM-DD";
            default -> field.answer().format() == null ? "text" : "text, in this form: " + field.answer().format();
        };
    }

    private static String describeBounds(final ExtractionField field)
    {
        final Double min = field.answer().minValue();
        final Double max = field.answer().maxValue();
        if (min != null && max != null) {
            return " from " + format(min) + " to " + format(max);
        } else if (min != null) {
            return " of at least " + format(min);
        } else if (max != null) {
            return " of at most " + format(max);
        }
        return "";
    }

    /** A bound as a person writes it: {@code 5}, not {@code 5.0}. */
    private static String format(final double number)
    {
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    private static long calculateMaxOutputTokens(final List<ExtractionField> fields)
    {
        return BASE_OUTPUT_TOKENS + OUTPUT_TOKENS_PER_FIELD * fields.size();
    }

    /**
     * The groups as they are asked: in order, each cut into calls of at most {@link #MAX_FIELDS_PER_CALL}
     * questions, and a group with no questions dropped.
     */
    private static List<Group> split(final List<Group> groups)
    {
        final List<Group> batches = new ArrayList<>();
        for (final Group group : groups) {
            final List<ExtractionField> fields = group.fields();
            for (int start = 0; start < fields.size(); start += MAX_FIELDS_PER_CALL) {
                batches.add(new Group(group.name(),
                    fields.subList(start, Math.min(fields.size(), start + MAX_FIELDS_PER_CALL))));
            }
        }
        return batches;
    }

    private static void appendLine(final StringBuilder block, final String label, @Nullable final String value)
    {
        if (value != null && !value.isBlank()) {
            block.append(label).append(": ").append(value).append('\n');
        }
    }
}
