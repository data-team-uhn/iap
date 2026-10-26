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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

/**
 * One question as the model is asked it.
 *
 * <p>Everything about the answer's form comes from the question itself: its data type, how many answers it takes,
 * its bounds, its pattern and its options. The schema author's {@code extractionPrompt} only has to say where the
 * answer is found and how to tell it, never what shape it takes.</p>
 *
 * @param key the name the answer is reported under, unique within one call
 * @param text the question as the submitter reads it
 * @param description the help text shown with the question, or {@code null}
 * @param purpose what the answer is used for, or {@code null}
 * @param instructions where the answer is found and how to tell it, the question's {@code extractionPrompt}
 * @param answer what form the answer takes
 * @version $Id$
 * @since 0.1.0
 */
record ExtractionField(@NotNull String key, @NotNull String text, @Nullable String description,
    @Nullable String purpose, @NotNull String instructions, @NotNull Answer answer)
{
    /** The data type a question has when it names none, as the node type has it. */
    static final String TEXT = "text";

    /** A data type for answers that are whole numbers. */
    static final String LONG = "long";

    /** A data type for answers that are numbers with a fraction. */
    static final String DOUBLE = "double";

    /** A data type for yes or no answers. */
    static final String BOOLEAN = "boolean";

    /** A data type for answers that are dates. */
    static final String DATE = "date";

    /**
     * What form an answer takes, all of it read off the question.
     *
     * @param dataType one of {@code text}, {@code long}, {@code double}, {@code boolean}, {@code date}
     * @param multiple whether the question takes several answers
     * @param minValue the smallest number accepted, or {@code null}
     * @param maxValue the largest number accepted, or {@code null}
     * @param format what a text answer must look like, in words when the schema says so, or {@code null}
     * @param options the answers the question offers, empty when it is answered freely
     * @version $Id$
     * @since 0.1.0
     */
    record Answer(@NotNull String dataType, boolean multiple, @Nullable Double minValue, @Nullable Double maxValue,
        @Nullable String format, @NotNull List<Option> options)
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
    record Option(@NotNull String value, @NotNull String label, @Nullable String description)
    {
    }

    /**
     * Whether the model may be asked a question at all. Only a question with instructions is: the rest are meant
     * for people.
     *
     * @param question a schema question
     * @return {@code true} when the question has an {@code extractionPrompt}
     */
    static boolean isExtractable(@NotNull final Question question)
    {
        return StringUtils.isNotBlank(question.getExtractionPrompt());
    }

    /**
     * The field asking a question.
     *
     * @param key the name the answer is reported under, see {@link #keyFor}
     * @param question an {@link #isExtractable extractable} question
     * @return the field
     */
    @NotNull
    static ExtractionField of(@NotNull final String key, @NotNull final Question question)
    {
        final String pattern = StringUtils.defaultIfBlank(question.getPatternMessage(), question.getPattern());
        return new ExtractionField(key, question.getText(), trim(question.getDescription()),
            trim(question.getPurpose()), StringUtils.trimToEmpty(question.getExtractionPrompt()),
            new Answer(StringUtils.defaultIfBlank(question.getDataType(), TEXT), question.isMultiple(),
                question.getMinValue(), question.getMaxValue(), trim(pattern),
                question.getOptions().stream().map(ExtractionField::toOption).toList()));
    }

    /**
     * The name a question's answer is reported under: its path inside the schema version, with dots for slashes.
     * Two sections that both ask for a {@code title} stay apart.
     *
     * @param schemaVersionPath the path of the schema version the question belongs to
     * @param question a question of that version
     * @return the key, such as {@code common.title}
     */
    @NotNull
    static String keyFor(@NotNull final String schemaVersionPath, @NotNull final Question question)
    {
        return Strings.CS.removeStart(question.getPath(), schemaVersionPath + "/").replace('/', '.');
    }

    private static Option toOption(final AnswerOption option)
    {
        return new Option(option.getValue(), StringUtils.defaultIfBlank(option.getLabel(), option.getValue()),
            trim(option.getDescription()));
    }

    private static String trim(final String text)
    {
        return text == null ? null : StringUtils.trimToNull(text.replaceAll("\\s+", " "));
    }
}
