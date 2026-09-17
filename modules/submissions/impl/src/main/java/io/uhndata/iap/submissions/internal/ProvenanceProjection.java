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
package io.uhndata.iap.submissions.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Evidence;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Where a pre-filled answer came from, as the form shows it: what the model suggested, how sure it was, and
 * the quotes behind it.
 *
 * <p>Only for answers a model read. A question the submitter answered themselves has no extraction under it
 * and gets no block, which is what "nobody suggested this" looks like on the wire.
 *
 * <p>An answer read more than once keeps an extraction per attempt. The one shown is the surest.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ProvenanceProjection
{
    private static final String CONFIDENCE = "confidence";

    private ProvenanceProjection()
    {
        // Utility
    }

    /**
     * The provenance of every answer a model read, by question path.
     *
     * @param submission the submission
     * @return one entry per question a model answered, empty when none was
     */
    static Map<String, JsonObject> of(final Submission submission)
    {
        final Map<String, JsonObject> blocks = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            final Extraction extraction = surest(answer);
            if (question != null && extraction != null) {
                blocks.put(question.getPath(), describe(extraction));
            }
        }
        return blocks;
    }

    /** The attempt the form shows: the one that was surest of itself. */
    private static Extraction surest(final Answer answer)
    {
        Extraction best = null;
        for (final Extraction extraction : answer.getChildren(Extraction.class)) {
            if (best == null || score(extraction) > score(best)) {
                best = extraction;
            }
        }
        return best;
    }

    private static double score(final Extraction extraction)
    {
        final Double confidence = extraction.getConfidence();
        return confidence == null ? 0.0 : confidence;
    }

    private static JsonObject describe(final Extraction extraction)
    {
        final JsonArrayBuilder passages = Json.createArrayBuilder();
        for (final Evidence evidence : extraction.getEvidence()) {
            passages.add(describe(evidence));
        }
        final JsonArrayBuilder suggested = Json.createArrayBuilder();
        if (extraction.getExtractedAnswer() != null) {
            suggested.add(extraction.getExtractedAnswer());
        }
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("suggested", suggested)
            .add(CONFIDENCE, score(extraction))
            .add("passages", passages)
            .add("reviewed", extraction.isReviewed())
            .add("evidenceRejected", extraction.isEvidenceRejected());
        if (extraction.getReasoning() != null) {
            json.add("reasoning", extraction.getReasoning());
        }
        return json.build();
    }

    private static JsonObject describe(final Evidence evidence)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("quote", Objects.toString(evidence.getQuote(), ""));
        final String cite = cite(evidence);
        if (!cite.isEmpty()) {
            json.add("cite", cite);
        }
        return json.build();
    }

    /**
     * Where the passage lives, in one phrase: the page, the nearest heading, or both. A DOCX carries no
     * pages, so this is often just a heading, and sometimes nothing at all. The chunk's summary is not used,
     * because a summary says what a passage is about rather than where it is.
     */
    private static String cite(final Evidence evidence)
    {
        final List<String> parts = new ArrayList<>(2);
        if (evidence.getPage() != null) {
            parts.add("p. " + evidence.getPage());
        }
        final String heading = heading(evidence);
        if (!heading.isEmpty()) {
            parts.add(heading);
        }
        return String.join(" · ", parts);
    }

    private static String heading(final Evidence evidence)
    {
        final String header = evidence.getHeader();
        return header == null || header.isBlank() ? "" : header.strip();
    }
}
