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
package io.uhndata.iap.submissions.internal.describers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Context;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;

/**
 * Where a suggested answer came from, as the form shows it beside the question: the newest run's values, what is
 * offered beside the submitter's own answer, how sure the model was, and the quotes behind it.
 *
 * <p>Only the newest run counts ({@link Answer#getLatestExtraction()}), and only when it found something. What is
 * offered follows {@link Answer#getOfferedSuggestion()}, so the form never decides that rule a second time.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ProvenanceProjection
{
    private ProvenanceProjection()
    {
        // Static helper
    }

    /**
     * The provenance of one answer.
     *
     * @param answer the answer, may be {@code null} when the question has none yet
     * @return its JSON, empty when no run found anything for it
     */
    static Optional<JsonObjectBuilder> describe(final Answer answer)
    {
        final Extraction latest = answer == null ? null : answer.getLatestExtraction();
        if (latest == null || latest.getExtractedValues().isEmpty()) {
            return Optional.empty();
        }
        // The PDF of each revision the run read, in the order it read them
        final Map<String, String> pdfs = new LinkedHashMap<>();
        latest.getSources().forEach(version -> findPdf(version).ifPresent(pdf -> pdfs.put(version.getPath(), pdf)));
        final JsonArrayBuilder passages = Json.createArrayBuilder();
        latest.getContext().forEach(context -> passages.add(describe(context, pdfs)));
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("suggested", Json.createArrayBuilder(latest.getExtractedValues()))
            .add("offered", Json.createArrayBuilder(answer.getOfferedSuggestion()))
            .add("confidence", Objects.requireNonNullElse(latest.getConfidence(), 0.0))
            .add("evidenceRejected", latest.isEvidenceRejected())
            .add("actedOn", latest.isActedOn())
            .add("passages", passages);
        Optional.ofNullable(latest.getReasoning()).ifPresent(reasoning -> json.add("reasoning", reasoning));
        return Optional.of(json);
    }

    private static JsonObjectBuilder describe(final Context context, final Map<String, String> pdfs)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("quote", Objects.toString(context.getQuote(), ""));
        final String cite = getCite(context);
        if (!cite.isEmpty()) {
            json.add("cite", cite);
        }
        // A quote that names its own revision links there, since a run reading two documents has quotes from
        // both. Otherwise the first revision the run read that has a PDF
        final EntityPart own = context.getSource();
        final String pdf = Optional.ofNullable(own == null ? null : pdfs.get(own.getPath()))
            .orElseGet(() -> pdfs.values().stream().findFirst().orElse(""));
        if (!pdf.isEmpty()) {
            // A PDF fragment every viewer understands; one without pages still opens the document
            json.add("source", context.getPage() == null ? pdf : pdf + "#page=" + context.getPage());
        }
        return json;
    }

    private static Optional<String> findPdf(final DocumentVersion version)
    {
        return Optional.ofNullable(version.getFile()).map(File::getPdfFile).map(Resource::getPath);
    }

    /** Where the passage is, in one phrase: the page, the nearest heading, or both. */
    private static String getCite(final Context context)
    {
        final List<String> parts = new ArrayList<>(2);
        if (context.getPage() != null) {
            parts.add("p. " + context.getPage());
        }
        final String header = context.getHeader();
        if (header != null && !header.isBlank()) {
            parts.add(header.strip());
        }
        return String.join(" · ", parts);
    }
}
