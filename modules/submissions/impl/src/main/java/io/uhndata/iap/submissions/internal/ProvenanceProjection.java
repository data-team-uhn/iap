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

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Evidence;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;
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
            final Extraction extraction = answer.getSurestExtraction();
            if (question != null && extraction != null) {
                blocks.put(question.getPath(), describe(extraction, answer.getSuggestedValues()));
            }
        }
        return blocks;
    }

    private static double score(final Extraction extraction)
    {
        final Double confidence = extraction.getConfidence();
        return confidence == null ? 0.0 : confidence;
    }

    private static JsonObject describe(final Extraction extraction, final List<String> values)
    {
        final String runSource = findSourcePdf(extraction);
        final JsonArrayBuilder passages = Json.createArrayBuilder();
        for (final Evidence evidence : extraction.getEvidence()) {
            // A passage that names its own document links there: a run that read the preamble and the
            // questionnaire as one text has two sources, and a quote belongs to one of them
            final String own = findPdf(evidence.getSource());
            passages.add(describe(evidence, own == null ? runSource : own));
        }
        // The values the suggestion stands for, not the model's reply as it came: the form compares this against
        // the live answer, and a multi-valued answer is stored as a value each
        final JsonArrayBuilder suggested = Json.createArrayBuilder();
        values.forEach(suggested::add);
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

    private static JsonObject describe(final Evidence evidence, final String source)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("quote", Objects.toString(evidence.getQuote(), ""));
        final String cite = cite(evidence);
        if (!cite.isEmpty()) {
            json.add("cite", cite);
        }
        if (source != null) {
            // The page as a PDF fragment, which every viewer that has pages understands. One that does not
            // still opens the document, so the link never promises more than it delivers
            json.add("source", evidence.getPage() == null ? source : source + "#page=" + evidence.getPage());
        }
        return json.build();
    }

    /**
     * The PDF the run read, for a link back to the passage. The first source that still has one: a run reads
     * one document, and a source whose PDF has gone is a revision the submitter has replaced.
     *
     * @param extraction the run
     * @return the PDF's path, or {@code null} when none of its sources has one
     */
    private static String findSourcePdf(final Extraction extraction)
    {
        for (final DocumentVersion version : extraction.getSources()) {
            final String pdf = findPdf(version);
            if (pdf != null) {
                return pdf;
            }
        }
        return null;
    }

    /**
     * The PDF of one revision.
     *
     * @param version the revision, may be {@code null}
     * @return the PDF's path, or {@code null} when there is none
     */
    private static String findPdf(final DocumentVersion version)
    {
        final File file = version == null ? null : version.getFile();
        final Resource pdf = file == null ? null : file.getFilePdf();
        return pdf == null ? null : pdf.getPath();
    }

    /**
     * Where the passage lives, in one phrase: the page, the nearest heading, or both. A DOCX carries no
     * pages, so this is often just a heading, and sometimes nothing at all.
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
