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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongBinaryOperator;
import java.util.regex.Pattern;

import io.uhndata.iap.llm.CallBudget;

/**
 * Turns a document and the questions asked of it into model calls.
 *
 * <p>Every call opens with the same text: the system prompt, then the document. Only what follows differs: the
 * reference text a step adds, then the questions. A provider caches the start of a prompt, so after the first call
 * on a document the document costs little, for the gate and for every intake call. The questions can then be asked in
 * several small calls of at most {@link #MAX_FIELDS_PER_CALL}. A small call gives a short reply, and a reply that
 * cannot be read loses only its own questions.</p>
 *
 * <p>The document is fitted once, against the largest call, so it is the same in every call.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionPrompt
{
    /** The most questions one call asks. A group with more is asked in several calls. */
    static final int MAX_FIELDS_PER_CALL = 10;

    /** The reply's room for its braces, and for a model that writes more than it was asked. */
    static final long BASE_OUTPUT_TOKENS = 500;

    /** The reply's room for each question: its quotes, its reasoning and its value. */
    static final long OUTPUT_TOKENS_PER_FIELD = 400;

    private static final String DOCUMENT_START = "<document>\n";

    private static final String DOCUMENT_END = "\n</document>";

    /** The tags of the blocks a call is made of, written loosely, as a model would still read them. */
    private static final Pattern BLOCK_TAG_PATTERN =
        Pattern.compile("<(\\s*/?\\s*(?:document|reference|questions)\\s*)>", Pattern.CASE_INSENSITIVE);

    private static final String PARAGRAPH_BREAK = "\n\n";

    private ExtractionPrompt()
    {
        // Utility
    }

    /**
     * Questions asked together, such as those of one requirement.
     *
     * @param name what the questions have in common, for the log
     * @param fields the questions, in the order they are asked
     * @version $Id$
     * @since 0.1.0
     */
    record Group(String name, List<ExtractionField> fields)
    {
    }

    /**
     * One model call.
     *
     * @param group the name of the group the questions come from
     * @param fields the questions this call asks
     * @param systemPrompt the system prompt
     * @param opening the document, then the reference text if any: what every call of the plan opens with
     * @param responseSchema the schema the reply must follow, see {@link ResponseSchema}
     * @param maxOutputTokens the room the reply is given
     * @version $Id$
     * @since 0.1.0
     */
    record Call(String group, List<ExtractionField> fields, String systemPrompt, String opening,
        String responseSchema, long maxOutputTokens)
    {
        /**
         * What the model is shown: the opening, then the questions.
         *
         * @return the user message
         */
        String userMessage()
        {
            return this.opening + buildQuestionBlock(this.fields);
        }
    }

    /**
     * The calls that ask every question of a document.
     *
     * @param calls the calls, empty when there is nothing to ask or no room to show the document
     * @param document the document as every call carries it, and how much of it had to be left out
     * @version $Id$
     * @since 0.1.0
     */
    record Plan(List<Call> calls, CallBudget.FittedText document)
    {
    }

    /**
     * Plans the calls that ask every question of a document.
     *
     * @param markdown the document's text
     * @param references reference text for these questions, sent after the document, or {@code null} for none
     * @param groups the questions, grouped as they should be asked
     * @param documentBudget gives how many tokens the document may use, from the tokens of everything else in the
     *     call and the room the reply needs
     * @return the plan; no calls when there is nothing to ask, the document is blank, or it does not fit at all
     */
    static Plan plan(final String markdown, final String references, final List<Group> groups,
        final LongBinaryOperator documentBudget)
    {
        final List<Group> batches = split(groups);
        if (batches.isEmpty() || markdown.isBlank()) {
            return new Plan(List.of(), new CallBudget.FittedText("", false, 0));
        }
        final String systemPrompt = readSystemPrompt();
        final String referenceBlock = buildReferenceBlock(references);
        final long largestQuestionBlock = batches.stream()
            .mapToLong(batch -> CallBudget.estimateTokens(buildQuestionBlock(batch.fields()))).max().orElse(0);
        final long largestReply = batches.stream()
            .mapToLong(batch -> calculateMaxOutputTokens(batch.fields())).max().orElse(0);
        final long promptTokens = CallBudget.estimateTokens(systemPrompt)
            + CallBudget.estimateTokens(DOCUMENT_START + DOCUMENT_END + PARAGRAPH_BREAK + referenceBlock)
            + largestQuestionBlock;
        final CallBudget.FittedText document = CallBudget.fitToTokens(markdown.strip(),
            documentBudget.applyAsLong(promptTokens, largestReply));
        if (document.text().isEmpty()) {
            return new Plan(List.of(), document);
        }
        final String opening = buildDocumentBlock(document.text()) + PARAGRAPH_BREAK + referenceBlock;
        final List<Call> calls = batches.stream()
            .map(batch -> new Call(batch.name(), batch.fields(), systemPrompt, opening,
                ResponseSchema.build(batch.fields()), calculateMaxOutputTokens(batch.fields())))
            .toList();
        return new Plan(calls, document);
    }

    /**
     * Splits a call into two that ask half its questions each. Each half keeps the whole call's room for its reply,
     * so a reply that ran out of room gets twice as much per question.
     *
     * @param call a call of at least two questions
     * @return the two halves, in order
     */
    static List<Call> halve(final Call call)
    {
        final int middle = call.fields().size() / 2;
        return List.of(call.fields().subList(0, middle), call.fields().subList(middle, call.fields().size()))
            .stream()
            .map(fields -> new Call(call.group(), fields, call.systemPrompt(), call.opening(),
                ResponseSchema.build(fields), call.maxOutputTokens()))
            .toList();
    }

    /**
     * The fixed system prompt every call opens with.
     *
     * @return the prompt, from the bundle's {@code /prompts} folder
     */
    static String readSystemPrompt()
    {
        return Prompts.read(Prompts.EXTRACTION_SYSTEM_FILE_NAME).strip();
    }

    /**
     * The document as a call carries it. A block tag inside the text is written as plain text, so the document
     * cannot end its own block, or open a block of questions, and pass what follows off as instructions.
     *
     * @param text the document's text
     * @return the block
     */
    static String buildDocumentBlock(final String text)
    {
        return DOCUMENT_START + BLOCK_TAG_PATTERN.matcher(text).replaceAll("&lt;$1&gt;") + DOCUMENT_END;
    }

    /**
     * The reference text as a call carries it, followed by a break.
     *
     * @param references the reference text, or {@code null}
     * @return the block, empty when there is no reference text
     */
    static String buildReferenceBlock(final String references)
    {
        return references == null || references.isBlank() ? ""
            : "<reference>\n" + references.strip() + "\n</reference>" + PARAGRAPH_BREAK;
    }

    /**
     * The questions as a call asks them.
     *
     * @param fields the questions
     * @return the block, ending with the request to answer them all
     */
    static String buildQuestionBlock(final List<ExtractionField> fields)
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
     * What form a field's answer takes, in words. This is the half of the schema the model reads.
     *
     * @param field the field
     * @return the description, followed by the options when there are any
     */
    static String describeAnswer(final ExtractionField field)
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
            case ExtractionField.LONG_TYPE -> "a whole number" + describeBounds(field);
            case ExtractionField.DOUBLE_TYPE -> "a number" + describeBounds(field);
            case ExtractionField.BOOLEAN_TYPE -> "true or false";
            case ExtractionField.DATE_TYPE -> "a date, as YYYY-MM-DD";
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

    /** The groups as they are asked: in order, each cut into calls of at most {@link #MAX_FIELDS_PER_CALL}. */
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

    private static void appendLine(final StringBuilder block, final String label, final String value)
    {
        if (value != null && !value.isBlank()) {
            block.append(label).append(": ").append(value).append('\n');
        }
    }
}
