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

import io.uhndata.iap.schemas.models.Question;

/**
 * One answer to be read out of a submitted document, as the schema asks for it.
 *
 * <p>A question is only worth putting to a model if the schema says how to ask it, so a question with no
 * extraction prompt is not an extraction field at all — that is what {@link #isExtractable} decides, and it is
 * what keeps questions meant for the submitter out of the model's way.
 *
 * @param name the question's node name, which identifies the answer it produces
 * @param text the question as the submitter would see it
 * @param purpose what the answer is used to judge, in the reviewer's terms
 * @param prompt what the model is told to look for
 * @param responseShape the JSON schema the answer must take, or {@code null} when the schema names none
 * @param rubricTags the parts of a proposal that may hold the answer
 * @param multiple whether more than one value may be given
 * @version $Id$
 * @since 0.1.0
 */
record ExtractionField(String name, String text, String purpose, String prompt, String responseShape,
    List<String> rubricTags, boolean multiple)
{
    /**
     * Takes a copy of the tags, so a field cannot be changed once it has been read from the schema.
     *
     * @param name the question's node name, which identifies the answer it produces
     * @param text the question as the submitter would see it
     * @param purpose what the answer is used to judge, in the reviewer's terms
     * @param prompt what the model is told to look for
     * @param responseShape the JSON schema the answer must take, or {@code null} when the schema names none
     * @param rubricTags the parts of a proposal that may hold the answer
     * @param multiple whether more than one value may be given
     */
    ExtractionField
    {
        rubricTags = List.copyOf(rubricTags);
    }

    /**
     * Whether a question is one an extraction stage should try to answer.
     *
     * @param question the question to weigh up
     * @return {@code true} when the schema says how to ask it of a document
     */
    static boolean isExtractable(final Question question)
    {
        final String prompt = question.getExtractionPrompt();
        return prompt != null && !prompt.isBlank();
    }

    /**
     * Read a question as an extraction field.
     *
     * @param question the question, which must be {@link #isExtractable}
     * @return the field to put to a model
     */
    static ExtractionField of(final Question question)
    {
        return new ExtractionField(question.getName(), question.getText(), question.getPurpose(),
            question.getExtractionPrompt(), question.getResponseShape(), question.getRubricTags(),
            question.isMultiple());
    }
}
