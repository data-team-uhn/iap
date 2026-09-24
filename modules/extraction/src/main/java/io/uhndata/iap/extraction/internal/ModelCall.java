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
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

/**
 * Asking a model once, and once more when the first answer was not the shape it had to be.
 *
 * <p>All three stages ask the same way: send the system prompt and one user message, try to read the reply, and
 * on failure send the same message again with a correction appended saying what was wrong. Written out three
 * times it was three chances for the stages to drift apart on how many attempts they make, whether the second
 * attempt keeps the schema, and what a second unreadable answer means.</p>
 *
 * <p>Two attempts, not more. Each one re-sends the whole document, so a third would pay for the document a
 * third time to ask a model that has now failed twice.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ModelCall
{
    /** What a stage appends when it has nothing more specific to say about an unreadable answer. */
    static final String SHAPE_CORRECTION =
        "Your previous answer was not a single JSON object matching the schema. "
            + "Answer again with exactly one such object and nothing else.";

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCall.class);

    private static final String CORRECTION_OPENING = "\n\n# Correction\n\n";

    private ModelCall()
    {
        // Utility
    }

    /**
     * Ask, and ask again with a correction if the answer could not be read.
     *
     * @param <T> what a readable answer becomes
     * @param client the client to ask through
     * @param system the system prompt, the same on both attempts so a cached prefix still matches
     * @param userMessage what to show the model
     * @param options the per-call options, including the schema the reply must match
     * @param read turns a reply into an answer, or {@code null} when it cannot be read
     * @param fault what to tell the model was wrong, given its unreadable first reply, or {@code null} to say
     *            only that the shape was wrong
     * @param stage what is asking, for the log
     * @return the answer, or {@code null} when neither attempt could be read
     * @throws IOException if the model cannot be reached
     */
    static <T> T askWithCorrection(final LLMClient client, final String system, final String userMessage,
        final LLMRequestOptions options, final Function<String, T> read, final Function<String, String> fault,
        final String stage) throws IOException
    {
        final String reply = client.chat(system, List.of(new LLMMessage("user", userMessage)), options);
        final T first = read.apply(reply);
        if (first != null) {
            return first;
        }
        // The re-ask says what was actually wrong rather than that something was. A model told its answer was
        // unreadable has nothing to correct; one told it held two objects, or stopped mid-string, does.
        final String wrong = fault == null ? SHAPE_CORRECTION : fault.apply(reply);
        LOGGER.warn("The {} did not answer in the required shape ({}); asking once more", stage, wrong);
        final T second = read.apply(client.chat(system,
            List.of(new LLMMessage("user", userMessage + CORRECTION_OPENING + wrong)), options));
        if (second == null) {
            LOGGER.warn("The {} did not answer in the required shape twice", stage);
        }
        return second;
    }
}
