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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

/**
 * Asks a document every question of a plan, one call at a time, and gathers the answers.
 *
 * <p>A reply that cannot be read is asked for once more, with a note saying what was wrong. The note goes after
 * the questions, so the second attempt opens with the same bytes as the first. A second bad reply, or a call that
 * fails outright, loses that call's questions only: the other calls still go ahead.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentReader
{
    private DocumentReader()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * How one call went.
     *
     * @version $Id$
     * @since 0.1.0
     */
    enum Status
    {
        /** The reply was read. */
        ANSWERED,
        /** Twice, the reply could not be read. */
        UNREADABLE,
        /** The model could not be asked. */
        FAILED
    }

    /**
     * One call and how it went.
     *
     * @param call the call
     * @param status how it went
     * @param attempts how many times the model was asked
     * @param problem why it did not go through, or {@code null} when it did
     * @version $Id$
     * @since 0.1.0
     */
    record Outcome(@NotNull ExtractionPrompt.Call call, @NotNull Status status, int attempts, @Nullable String problem)
    {
    }

    /**
     * Everything a reading found.
     *
     * @param answers the answer to each question whose call went through, in the order they were asked
     * @param outcomes how each call went, in the order they were made
     * @version $Id$
     * @since 0.1.0
     */
    record Reading(@NotNull Map<String, ExtractionReply.FieldReply> answers, @NotNull List<Outcome> outcomes)
    {
    }

    /**
     * Ask every question of a plan.
     *
     * @param client the model
     * @param plan the calls to make
     * @param document the document as it was parsed, which the quotes are checked against
     * @return what was found
     */
    @NotNull
    static Reading read(@NotNull final LLMClient client, @NotNull final ExtractionPrompt.Plan plan,
        @NotNull final DocumentScan document)
    {
        final Map<String, ExtractionReply.FieldReply> answers = new LinkedHashMap<>();
        final List<Outcome> outcomes = new ArrayList<>();
        for (final ExtractionPrompt.Call call : plan.calls()) {
            outcomes.add(ask(client, call, document, answers));
        }
        return new Reading(answers, outcomes);
    }

    private static Outcome ask(final LLMClient client, final ExtractionPrompt.Call call, final DocumentScan document,
        final Map<String, ExtractionReply.FieldReply> answers)
    {
        final LLMRequestOptions options = LLMRequestOptions.builder().maxOutputTokens(call.maxOutputTokens())
            .jsonSchema(ResponseSchema.NAME, call.responseSchema()).build();
        String message = call.userMessage();
        String problem = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            final String reply;
            try {
                reply = client.chat(call.systemPrompt(), List.of(new LLMMessage("user", message)), options);
            } catch (final IOException e) {
                return new Outcome(call, Status.FAILED, attempt, e.getMessage());
            }
            final Map<String, ExtractionReply.FieldReply> read = ExtractionReply.read(call.fields(), reply, document);
            if (read != null) {
                answers.putAll(read);
                return new Outcome(call, Status.ANSWERED, attempt, null);
            }
            problem = ExtractionReply.describeUnreadable(reply);
            message = call.userMessage() + "\n\n# Correction\n\nYour previous reply could not be read: " + problem
                + ". Reply with one JSON object that follows the schema, and nothing else.";
        }
        return new Outcome(call, Status.UNREADABLE, 2, problem);
    }
}
