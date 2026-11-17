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
import java.util.regex.Pattern;

import io.uhndata.iap.schemas.models.OfferedOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Validation;

/**
 * One schema question, ready to ask the model. The model's answer is saved as an {@code Extraction}, not here.
 *
 * <p>The form of the answer comes from the question itself: its data type, how many answers it takes, its bounds,
 * its pattern and its options. The question's {@code extractionPrompt} only has to say where the answer is found and
 * how to tell it. Only questions with an extraction prompt are asked ({@link #isExtractable}); the others are for the
 * submitter. A classification is the one exception: its instructions are on the requirement, not the question.</p>
 *
 * @param key the name the answer comes back under, unique within one call, see {@link #getKey}
 * @param text the question as the submitter reads it
 * @param description the help text shown with the question, or {@code null}
 * @param purpose what the answer is used for, or {@code null}
 * @param instructions where the answer is found and how to tell it
 * @param answer what form the answer takes
 * @version $Id$
 * @since 0.1.0
 */
record ExtractionField(String key, String text, String description, String purpose, String instructions,
    Answer answer)
{
    /** The data type of a question that names none. */
    static final String TEXT_TYPE = "text";

    /** The data type for whole numbers. */
    static final String LONG_TYPE = "long";

    /** The data type for numbers with a fraction. */
    static final String DOUBLE_TYPE = "double";

    /** The data type for yes or no answers. */
    static final String BOOLEAN_TYPE = "boolean";

    /** The data type for dates. */
    static final String DATE_TYPE = "date";

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * What form an answer takes, all of it read off the question.
     *
     * @param dataType one of {@code text}, {@code long}, {@code double}, {@code boolean}, {@code date}
     * @param multiple whether the question takes several answers
     * @param minValue the smallest number accepted, or {@code null}
     * @param maxValue the largest number accepted, or {@code null}
     * @param format what a text answer must look like, in words, or {@code null}
     * @param options the answers the question offers, empty when it is answered freely
     * @version $Id$
     * @since 0.1.0
     */
    record Answer(String dataType, boolean multiple, Double minValue, Double maxValue, String format,
        List<Option> options)
    {
    }

    /**
     * One answer a question offers.
     *
     * @param value what is stored when it is picked, and what the model must answer with
     * @param label what the submitter reads
     * @param description what the option covers, or {@code null}
     * @version $Id$
     * @since 0.1.0
     */
    record Option(String value, String label, String description)
    {
    }

    /**
     * Whether the model may be asked this question. Only a question with an extraction prompt is; the rest are
     * meant for people.
     *
     * @param question a schema question
     * @return {@code true} when the question has a non-blank {@code extractionPrompt}
     */
    static boolean isExtractable(final Question question)
    {
        return !isBlank(question.getExtractionPrompt());
    }

    /**
     * The field for a question, asked with the question's own extraction prompt.
     *
     * @param key the name the answer comes back under, see {@link #getKey}
     * @param question an {@link #isExtractable extractable} question
     * @return the field
     */
    static ExtractionField of(final String key, final Question question)
    {
        return of(key, question, question.getExtractionPrompt());
    }

    /**
     * The field for a question, asked with the given instructions. Used for a classification, whose instructions
     * are on the requirement, not on the question its answer is saved under.
     *
     * @param key the name the answer comes back under, see {@link #getKey}
     * @param question the question the answer is saved under
     * @param instructions what the model is told to look for or decide
     * @return the field
     */
    static ExtractionField of(final String key, final Question question, final String instructions)
    {
        final String pattern = isBlank(question.getPatternMessage()) ? question.getPattern()
            : question.getPatternMessage();
        final String dataType = isBlank(question.getDataType()) ? TEXT_TYPE : question.getDataType();
        return new ExtractionField(key, question.getText(), collapseWhitespace(question.getDescription()),
            collapseWhitespace(question.getPurpose()), instructions == null ? "" : instructions.strip(),
            new Answer(dataType, question.isMultiple(), question.getMinValue(), question.getMaxValue(),
                collapseWhitespace(pattern),
                question.getOfferedOptions().stream().map(ExtractionField::toOption).toList()));
    }

    /**
     * A field the model answers with {@code yes} or {@code no}, for a decision that is not a question, such as a
     * validation.
     *
     * @param key the name the answer comes back under
     * @param text what is being decided, as a person reads it
     * @param instructions what the model is told to look for, and how to decide
     * @return the field
     */
    static ExtractionField createYesNo(final String key, final String text, final String instructions)
    {
        return new ExtractionField(key, text, null, null, instructions == null ? "" : instructions.strip(),
            new Answer(TEXT_TYPE, false, null, null, null, List.of(new Option(Validation.OUTCOME_YES, "Yes", null),
                new Option(Validation.OUTCOME_NO, "No", null))));
    }

    /**
     * The name a question's answer comes back under: its path inside the schema version, with dots for slashes.
     * Two sections that both ask for a {@code title} stay apart.
     *
     * @param schemaVersionPath the path of the schema version the question belongs to
     * @param question a question of that version
     * @return the key, such as {@code common.title}
     */
    static String getKey(final String schemaVersionPath, final Question question)
    {
        final String path = question.getPath();
        final String prefix = schemaVersionPath + "/";
        return (path.startsWith(prefix) ? path.substring(prefix.length()) : path).replace('/', '.');
    }

    private static Option toOption(final OfferedOption option)
    {
        return new Option(option.value(), isBlank(option.label()) ? option.value() : option.label(),
            collapseWhitespace(option.description()));
    }

    /** The text with its whitespace collapsed, or {@code null} when nothing is left. */
    private static String collapseWhitespace(final String text)
    {
        if (text == null) {
            return null;
        }
        final String collapsed = WHITESPACE_PATTERN.matcher(text).replaceAll(" ").strip();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static boolean isBlank(final String text)
    {
        return text == null || text.isBlank();
    }
}
