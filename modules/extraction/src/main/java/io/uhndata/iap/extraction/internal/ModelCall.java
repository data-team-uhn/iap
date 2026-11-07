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
 * Asks a model, and asks once more if the first answer cannot be read. The second try sends the same message
 * with a note saying what was wrong.
 *
 * <p>Only two tries. Each one sends the whole document again, so a third try costs a lot for a model that has
 * already failed twice.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ModelCall
{
    /** The note sent with the second try when the caller has nothing more exact to say. */
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
     * Asks the model, and asks once more with a note if the first answer cannot be read.
     *
     * @param <T> the type of a readable answer
     * @param client the client used to ask the model
     * @param system the system prompt, the same on both tries so a cached prompt can be reused
     * @param userMessage the message to send the model
     * @param options the options for the call, including the schema the reply must match
     * @param read turns a reply into an answer, returning {@code null} when it cannot be read
     * @param fault builds the note from the unreadable first reply, or {@code null} to send
     *            {@link #SHAPE_CORRECTION}
     * @param stage the name of the step asking, for the logs
     * @return the answer, or {@code null} when neither reply could be read
     * @throws IOException if the model cannot be reached
     */
    static <T> T askWithCorrection(final LLMClient client, final String system, final String userMessage,
        final LLMRequestOptions options, final Function<String, T> read, final Function<String, String> fault,
        final String stage) throws IOException
    {
        final String reply = chat(client, system, userMessage, options);
        final T first = read.apply(reply);
        if (first != null) {
            return first;
        }
        // Say what exactly was wrong, so the model knows what to fix
        final String wrong = fault == null ? SHAPE_CORRECTION : fault.apply(reply);
        LOGGER.warn("The {} did not answer in the required shape ({}); asking once more", stage, wrong);
        final T second = read.apply(chat(client, system, userMessage + CORRECTION_OPENING + wrong, options));
        if (second == null) {
            LOGGER.warn("The {} did not answer in the required shape twice", stage);
        }
        return second;
    }

    /**
     * Sends one message to the model, unless this reading was stopped. A stop throws {@link ReadingRuns.Stopped},
     * before or after the call.
     *
     * @param client the client to ask through
     * @param system the system prompt
     * @param userMessage what to show the model
     * @param options the per-call options
     * @return the reply
     * @throws IOException if the model cannot be reached
     */
    private static String chat(final LLMClient client, final String system, final String userMessage,
        final LLMRequestOptions options) throws IOException
    {
        ReadingRuns.enterModelCall();
        final String reply;
        try {
            reply = client.chat(system, List.of(new LLMMessage("user", userMessage)), options);
        } catch (final IOException e) {
            final boolean stopped = ReadingRuns.isStopped(e);
            ReadingRuns.leaveModelCall();
            if (stopped) {
                throw new ReadingRuns.Stopped();
            }
            throw e;
        }
        // Not every client aborts on an interrupt, so a stop during the call must still reject the reply
        ReadingRuns.leaveModelCall();
        return reply;
    }
}
