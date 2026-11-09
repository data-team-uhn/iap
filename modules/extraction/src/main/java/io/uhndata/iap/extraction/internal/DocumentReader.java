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
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMRequestOptions;

/**
 * Asks a document every question of a plan, one call at a time, and gathers the answers.
 *
 * <p>A reply that cannot be read is asked for once more, with a note saying what was wrong. The note goes after the
 * questions, so the second try opens with the same text as the first and still uses the provider's cache. A reply
 * that ran out of room is not asked again as it was: its questions are asked in two halves instead, each with the
 * whole call's room. A second bad reply loses that call's questions only, and the other calls still go ahead. A call
 * that cannot reach the model stops the reading: the calls after it would only wait out the same outage.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentReader
{
    /** How many times one call is made before its questions are given up. */
    static final int MAX_TRIES = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentReader.class);

    private DocumentReader()
    {
        // Utility
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
        /** On every try, the reply could not be read. */
        UNREADABLE,
        /** The model could not be reached, by this call or by one before it. */
        FAILED
    }

    /**
     * One call and how it went.
     *
     * @param call the call
     * @param status how it went
     * @param attempts how many times the model was asked; 0 for a call not made because the model was unreachable
     * @param answers the answer to each of the call's questions, empty unless it was answered
     * @param problem why the reply could not be read, or {@code null} unless it was unreadable
     * @param failure why the model could not be reached, or {@code null} unless the call failed
     * @version $Id$
     * @since 0.1.0
     */
    record Outcome(ExtractionPrompt.Call call, Status status, int attempts, Map<String, FieldResult> answers,
        String problem, IOException failure)
    {
        static Outcome createAnswered(final ExtractionPrompt.Call call, final int attempts,
            final Map<String, FieldResult> answers)
        {
            return new Outcome(call, Status.ANSWERED, attempts, answers, null, null);
        }

        static Outcome createUnreadable(final ExtractionPrompt.Call call, final String problem)
        {
            return new Outcome(call, Status.UNREADABLE, MAX_TRIES, Map.of(), problem, null);
        }

        static Outcome createFailed(final ExtractionPrompt.Call call, final int attempts, final IOException failure)
        {
            return new Outcome(call, Status.FAILED, attempts, Map.of(), null, failure);
        }
    }

    /**
     * Everything a reading found.
     *
     * @param outcomes how each call went, in the order made
     * @version $Id$
     * @since 0.1.0
     */
    record Reading(List<Outcome> outcomes)
    {
        /**
         * The answers of every call that went through.
         *
         * @return the answer to each question, in the order asked
         */
        Map<String, FieldResult> answers()
        {
            final Map<String, FieldResult> answers = new LinkedHashMap<>();
            this.outcomes.forEach(outcome -> answers.putAll(outcome.answers()));
            return answers;
        }

        /**
         * Why the model could not be reached, if a call found it so.
         *
         * @return the first failure, or {@code null} when every call reached the model
         */
        IOException findFailure()
        {
            return this.outcomes.stream().map(Outcome::failure).filter(Objects::nonNull).findFirst().orElse(null);
        }

        /**
         * Why a reply could not be read, if one could not.
         *
         * @return the first problem, or {@code null} when every reply that came back was read
         */
        String findProblem()
        {
            return this.outcomes.stream().map(Outcome::problem).filter(Objects::nonNull).findFirst().orElse(null);
        }
    }

    /**
     * Asks every question of a plan.
     *
     * @param client the model
     * @param plan the calls to make
     * @param document the document as parsed, which the quotes are checked against
     * @return what was found, and how each call went
     */
    static Reading read(final LLMClient client, final ExtractionPrompt.Plan plan, final DocumentScan document)
    {
        final List<Outcome> outcomes = new ArrayList<>();
        for (final ExtractionPrompt.Call call : plan.calls()) {
            final IOException failure = new Reading(outcomes).findFailure();
            outcomes.addAll(failure == null ? ask(client, call, document)
                : List.of(Outcome.createFailed(call, 0, failure)));
        }
        return new Reading(outcomes);
    }

    private static List<Outcome> ask(final LLMClient client, final ExtractionPrompt.Call call,
        final DocumentScan document)
    {
        final LLMRequestOptions options = LLMRequestOptions.builder().maxOutputTokens(call.maxOutputTokens())
            .jsonSchema(ResponseSchema.SCHEMA_NAME, call.responseSchema()).build();
        String message = call.userMessage();
        String problem = null;
        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            final String reply;
            try {
                reply = ModelCall.ask(client, call.systemPrompt(), message, options);
            } catch (final IOException e) {
                return List.of(Outcome.createFailed(call, attempt, e));
            }
            try {
                return List.of(Outcome.createAnswered(call, attempt, readReply(call, reply, document)));
            } catch (final ExtractionReply.UnreadableReply e) {
                problem = e.getMessage();
                LOGGER.warn("A reply for {} could not be read ({}) on try {}", call.group(), problem, attempt);
                if (e.isCutOff() && call.fields().size() > 1) {
                    return ExtractionPrompt.halve(call).stream()
                        .flatMap(half -> ask(client, half, document).stream()).toList();
                }
            }
            message = call.userMessage() + "\n\n# Correction\n\nYour previous reply could not be read: " + problem
                + ". Reply with one JSON object that follows the schema, and nothing else.";
        }
        return List.of(Outcome.createUnreadable(call, problem));
    }

    /** Reads a reply. Anything that goes wrong reading it makes it unreadable, so it costs only its own call. */
    private static Map<String, FieldResult> readReply(final ExtractionPrompt.Call call, final String reply,
        final DocumentScan document) throws ExtractionReply.UnreadableReply
    {
        try {
            return ExtractionReply.read(call.fields(), reply, document);
        } catch (final RuntimeException e) {
            LOGGER.warn("A reply for {} broke the reader: {}", call.group(), e.getMessage(), e);
            throw new ExtractionReply.UnreadableReply("it could not be read", false);
        }
    }
}
