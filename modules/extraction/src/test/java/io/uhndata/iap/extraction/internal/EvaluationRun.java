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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import io.uhndata.iap.llm.CallBudget;

/**
 * What an evaluation found, added up one document at a time. The summary's counts and rates carry no document text;
 * each document's own line of the details does.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EvaluationRun
{
    private final List<ExtractionPrompt.Group> groups;

    private final String model;

    private final long window;

    private final long started = System.nanoTime();

    private final Map<String, Tally> tallies = new LinkedHashMap<>();

    private final Map<DocumentReader.Status, Integer> outcomes = new EnumMap<>(DocumentReader.Status.class);

    private final List<Long> documentTokens = new ArrayList<>();

    private int cut;

    private int unplanned;

    private int calls;

    private int retried;

    /** What one question added up to over every document. */
    private static final class Tally
    {
        private int asked;

        private int found;

        private int expected;

        private int correct;

        private double f1;

        private int quotesOffered;

        private int quotesFound;

        private int unsupported;

        private double confidenceRight;

        private int right;

        private double confidenceWrong;

        private int wrong;

        private void add(final ExtractionReply.FieldReply reply, final AnswerScoring.Score score)
        {
            this.asked++;
            final boolean isFound = reply != null && reply.isFound();
            if (reply != null) {
                this.found += isFound ? 1 : 0;
                this.unsupported += isFound && reply.quotes().isEmpty() ? 1 : 0;
                this.quotesOffered += reply.quotesOffered();
                this.quotesFound += reply.quotes().size();
            }
            if (!score.scored()) {
                return;
            }
            this.expected++;
            this.correct += score.correct() ? 1 : 0;
            this.f1 += score.f1();
            if (isFound && score.correct()) {
                this.confidenceRight += reply.confidence();
                this.right++;
            } else if (isFound) {
                this.confidenceWrong += reply.confidence();
                this.wrong++;
            }
        }

        private JsonObject summarize()
        {
            return Json.createObjectBuilder()
                .add("asked", this.asked).add("found", this.found).add("expected", this.expected)
                .add("correct", this.correct).add("accuracy", average(this.correct, this.expected))
                .add("meanF1", average(this.f1, this.expected))
                .add("quotesOffered", this.quotesOffered).add("quotesFound", this.quotesFound)
                .add("unsupported", this.unsupported)
                .add("confidenceWhenRight", average(this.confidenceRight, this.right))
                .add("confidenceWhenWrong", average(this.confidenceWrong, this.wrong))
                .build();
        }
    }

    EvaluationRun(final List<ExtractionPrompt.Group> groups, final String model, final long window)
    {
        this.groups = groups;
        this.model = model;
        this.window = window;
        groups.forEach(group -> group.fields().forEach(field -> this.tallies.put(field.key(), new Tally())));
    }

    /**
     * Add one document's reading.
     *
     * @param id the document's name
     * @param text the document's text
     * @param plan the calls it was asked in
     * @param reading what the calls found
     * @param wanted the expected answers, keyed by question
     * @param millis how long the reading took
     * @return the document's line of the details: every answer, what was expected, and the quotes
     */
    JsonObject add(final String id, final String text, final ExtractionPrompt.Plan plan,
        final DocumentReader.Reading reading, final JsonObject wanted, final long millis)
    {
        this.documentTokens.add(CallBudget.estimateTokens(text));
        this.cut += plan.document().wasCut() ? 1 : 0;
        this.unplanned += plan.calls().isEmpty() ? 1 : 0;
        final JsonArrayBuilder callLines = Json.createArrayBuilder();
        for (final DocumentReader.Outcome outcome : reading.outcomes()) {
            this.outcomes.merge(outcome.status(), 1, Integer::sum);
            this.calls++;
            this.retried += outcome.attempts() > 1 ? 1 : 0;
            callLines.add(Json.createObjectBuilder().add("group", outcome.call().group())
                .add("status", outcome.status().name()).add("attempts", outcome.attempts())
                .add("problem", outcome.problem() == null ? "" : outcome.problem()));
        }
        final JsonObjectBuilder answers = Json.createObjectBuilder();
        this.groups.forEach(group -> group.fields().forEach(field -> {
            final ExtractionReply.FieldReply reply = reading.answers().get(field.key());
            final List<String> values = reply == null ? List.of() : reply.values();
            final AnswerScoring.Score score = AnswerScoring.score(field, values, wanted.get(field.key()));
            this.tallies.get(field.key()).add(reply, score);
            answers.add(field.key(), describe(reply, values, wanted.getOrDefault(field.key(), JsonValue.NULL),
                score));
        }));
        return Json.createObjectBuilder().add("document", id).add("millis", millis)
            .add("cut", plan.document().wasCut()).add("omittedCharacters", plan.document().omittedCharacters())
            .add("calls", callLines).add("answers", answers).build();
    }

    private static JsonObject describe(final ExtractionReply.FieldReply reply, final List<String> values,
        final JsonValue expected, final AnswerScoring.Score score)
    {
        final JsonArrayBuilder quotes = Json.createArrayBuilder();
        if (reply != null) {
            reply.quotes().forEach(quote -> quotes.add(quote.text()));
        }
        final JsonValue correct = score.correct() ? JsonValue.TRUE : JsonValue.FALSE;
        return Json.createObjectBuilder()
            .add("values", Json.createArrayBuilder(values))
            .add("expected", expected)
            .add("correct", score.scored() ? correct : JsonValue.NULL)
            .add("f1", score.f1())
            .add("confidence", reply == null ? 0.0 : reply.confidence())
            .add("reasoning", reply == null ? "" : reply.reasoning())
            .add("quotesOffered", reply == null ? 0 : reply.quotesOffered())
            .add("quotes", quotes)
            .build();
    }

    /**
     * The counts and rates of the whole run, with no document text in them.
     *
     * @return the summary
     * @throws IOException when the system prompt cannot be read for its fingerprint
     */
    JsonObject summarize() throws IOException
    {
        final List<Long> sorted = this.documentTokens.stream().sorted().toList();
        final JsonObjectBuilder questions = Json.createObjectBuilder();
        this.tallies.forEach((key, tally) -> questions.add(key, tally.summarize()));
        final JsonObjectBuilder statuses = Json.createObjectBuilder();
        this.outcomes.forEach((status, count) -> statuses.add(status.name(), count));
        return Json.createObjectBuilder()
            .add("model", this.model)
            .add("promptSha256", fingerprint(ExtractionPrompt.readSystemPrompt()))
            .add("contextTokens", this.window)
            .add("documents", this.documentTokens.size()).add("documentsCut", this.cut)
            .add("documentsNotAsked", this.unplanned)
            .add("documentTokensMedian", sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2))
            .add("documentTokensMax", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1))
            .add("calls", this.calls).add("callsRetried", this.retried).add("callOutcomes", statuses)
            .add("seconds", (System.nanoTime() - this.started) / 1_000_000_000L)
            .add("questions", questions)
            .build();
    }

    /**
     * The summary as a Markdown table, one row per question.
     *
     * @param summary from {@link #summarize}
     * @return the table, with a line about the run above it
     */
    static String toMarkdown(final JsonObject summary)
    {
        final StringBuilder table = new StringBuilder(String.format("Model %s, prompt %s, %d documents (%d cut, %d"
            + " not asked, median %d tokens), %d calls (%d retried) %s, %d s%n%n", summary.getString("model"),
            summary.getString("promptSha256"), summary.getInt("documents"), summary.getInt("documentsCut"),
            summary.getInt("documentsNotAsked"), summary.getInt("documentTokensMedian"), summary.getInt("calls"),
            summary.getInt("callsRetried"), summary.getJsonObject("callOutcomes"), summary.getInt("seconds")));
        table.append("| Question | Found | Accuracy | Mean F1 | Quotes found | Unsupported | Confidence right/wrong |\n"
            + "| --- | --- | --- | --- | --- | --- | --- |\n");
        summary.getJsonObject("questions").forEach((key, value) -> {
            final JsonObject q = (JsonObject) value;
            table.append(String.format("| %s | %d/%d | %d/%d (%.0f%%) | %.2f | %d/%d | %d | %.2f / %.2f |%n", key,
                q.getInt("found"), q.getInt("asked"), q.getInt("correct"), q.getInt("expected"),
                100 * q.getJsonNumber("accuracy").doubleValue(), q.getJsonNumber("meanF1").doubleValue(),
                q.getInt("quotesFound"), q.getInt("quotesOffered"), q.getInt("unsupported"),
                q.getJsonNumber("confidenceWhenRight").doubleValue(),
                q.getJsonNumber("confidenceWhenWrong").doubleValue()));
        });
        return table.toString();
    }

    private static double average(final double total, final int count)
    {
        return count == 0 ? 0.0 : total / count;
    }

    private static String fingerprint(final String prompt)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }
}
