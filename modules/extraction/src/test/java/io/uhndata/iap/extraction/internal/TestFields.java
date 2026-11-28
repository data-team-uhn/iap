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

import java.util.Arrays;
import java.util.List;

import io.uhndata.iap.llm.CallBudget;

/**
 * Builds the questions the tests ask the model, and plans the calls that ask them. Each question reads
 * "What is {key}?" and asks to "Find {key}."
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestFields
{
    /** The options of a question about what kind of product a study tests. */
    static final List<ExtractionField.Option> DRUG_OR_DEVICE = List.of(
        new ExtractionField.Option("drug", "Drug", "Including biologics and vaccines"),
        new ExtractionField.Option("device", "Medical device", null));

    /** A context window big enough to hold every test document. */
    static final long WIDE_WINDOW = 200_000L;

    private TestFields()
    {
        // Utility class
    }

    /** Creates a question answered with one free text. */
    static ExtractionField createTextField(final String key)
    {
        return createField(key, ExtractionField.TEXT_TYPE);
    }

    /** Creates a question answered with one value of the given data type. */
    static ExtractionField createField(final String key, final String dataType)
    {
        return createField(key, new ExtractionField.Answer(dataType, false, null, null, null, List.of()));
    }

    /** Creates a question answered with several values of the given data type. */
    static ExtractionField createListField(final String key, final String dataType)
    {
        return createField(key, new ExtractionField.Answer(dataType, true, null, null, null, List.of()));
    }

    /** Creates a question answered with one of the options. */
    static ExtractionField createChoiceField(final String key, final List<ExtractionField.Option> options)
    {
        return createField(key,
            new ExtractionField.Answer(ExtractionField.TEXT_TYPE, false, null, null, null, options));
    }

    /** Creates a question answered with any of the options that apply. */
    static ExtractionField createMultipleChoiceField(final String key, final List<ExtractionField.Option> options)
    {
        return createField(key,
            new ExtractionField.Answer(ExtractionField.TEXT_TYPE, true, null, null, null, options));
    }

    /** Creates a question whose answer takes the given form. */
    static ExtractionField createField(final String key, final ExtractionField.Answer answer)
    {
        return new ExtractionField(key, "What is " + key + "?", null, null, "Find " + key + ".", answer);
    }

    /** Creates a group of free text questions, one per key. */
    static ExtractionPrompt.Group createGroup(final String name, final String... keys)
    {
        return new ExtractionPrompt.Group(name, Arrays.stream(keys).map(TestFields::createTextField).toList());
    }

    /** Plans the calls for a document under {@link #WIDE_WINDOW}, so every test document fits. */
    static ExtractionPrompt.Plan planInWideWindow(final String text, final List<ExtractionPrompt.Group> groups)
    {
        return ExtractionPrompt.plan(text, null, groups,
            (prompt, reply) -> CallBudget.calculateDocumentTokenBudget(WIDE_WINDOW, prompt, reply));
    }
}
