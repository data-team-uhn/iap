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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Helpers for the reading steps: which questions to ask the model, and saving what it read as runs.
 *
 * <p>Every {@code intakeAnswers} step uses them, so a schema read in stages is asked and saved the same way in
 * each stage. {@code classifyDocument} saves its picks through {@link #write} too.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionFields
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractionFields.class);

    private ExtractionFields()
    {
        // Utility
    }

    /**
     * The questions the model can be asked (those with an extraction prompt), keyed by their path within the
     * schema version, in schema order.
     *
     * @param submission the submission whose schema is read
     * @param requirement the requirement to ask about, or {@code null} for every question of the schema
     * @return the extractable questions, empty when the requirement has none
     */
    static Map<String, Question> getExtractable(final Submission submission, final String requirement)
    {
        final String versionPath = submission.getSchemaVersion().getPath();
        // Keep schema order: the model is shown the questions in this order
        final Map<String, Question> questions = new LinkedHashMap<>();
        final List<Question> asked = requirement == null || requirement.isBlank()
            ? submission.getQuestions() : submission.getQuestions(requirement);
        for (final Question question : asked) {
            if (ExtractionField.isExtractable(question)) {
                questions.put(question.getPath().substring(versionPath.length() + 1), question);
            }
        }
        return questions;
    }

    /**
     * The questions as fields to ask the model. Each field's name is the question's path within the schema
     * version, which is the key its answer comes back under.
     *
     * @param questions the questions to ask, by path within the schema version
     * @return the fields to ask the model, in the same order
     */
    static List<ExtractionField> toFields(final Map<String, Question> questions)
    {
        final List<ExtractionField> fields = new ArrayList<>(questions.size());
        questions.forEach((key, question) -> fields.add(ExtractionField.of(question, key)));
        return fields;
    }

    /**
     * Saves one run for each entry in {@code results}, found or not, under that question's answer. The intake
     * passes every question it asked; the gate passes only its sure picks. An entry whose key is not in
     * {@code questions} is skipped.
     *
     * <p>Each run names the document versions it read, which is how a later reading knows those versions were
     * already read for that question. No answer's value is written: what the model read stays a suggestion until
     * the submitter acts on it.</p>
     *
     * @param resolver the session to write with
     * @param target the submission node
     * @param submission the submission, for the answers it already has
     * @param questions what was asked, by path within the schema version
     * @param results what the model answered, keyed the same way; one run is saved per entry
     * @param files the documents the answers were read from
     */
    static void write(final ResourceResolver resolver, final Resource target, final Submission submission,
        final Map<String, Question> questions, final Map<String, FieldResult> results, final List<File> files)
    {
        final Map<String, Answer> answers = getAnswers(submission);
        for (final Map.Entry<String, FieldResult> entry : results.entrySet()) {
            final Question question = questions.get(entry.getKey());
            if (question == null) {
                continue;
            }
            // One field failing to write, such as when a source file stops resolving, must not lose the others
            try {
                final Answer existing = answers.get(question.getPath());
                ExtractedAnswers.write(resolver, target,
                    existing == null ? null : resolver.getResource(existing.getPath()), question, entry.getValue(),
                    files);
            } catch (final PersistenceException e) {
                LOGGER.warn("Could not record the run for {}: {}", question.getPath(), e.getMessage(), e);
                // The reading still says done, so a lost run would go unnoticed
                ErrorLogger.logError(e, ErrorContext.of(ExtractionFields.class, "write").about(target)
                    .with("question", question.getPath()));
            }
        }
    }

    /**
     * Whether the step reads a newer document version that its questions were not read from yet: a file that is
     * not its document's first version, and that no run of these questions cites. Each file is checked on its
     * own, so a newer version already read does not hide another one nobody has read.
     *
     * @param submission the submission
     * @param questions the questions the step asks, answered or not
     * @param files the files the step reads
     * @return {@code true} for the first reading of a newer version, which asks every question again
     */
    static boolean isNewRevision(final Submission submission, final Collection<Question> questions,
        final List<File> files)
    {
        final Set<String> revisions = files.stream()
            .map(File::getVersion)
            .filter(version -> version != null && version.getNumber() > 1)
            .map(DocumentVersion::getPath)
            .collect(Collectors.toSet());
        if (revisions.isEmpty()) {
            return false;
        }
        final Set<String> asked = questions.stream().map(Question::getPath).collect(Collectors.toSet());
        final Set<String> cited = submission.getAnswers().stream()
            .filter(answer -> isAsking(answer.getQuestion(), asked))
            .flatMap(answer -> answer.getExtractions().stream())
            .flatMap(extraction -> extraction.getSources().stream())
            .map(DocumentVersion::getPath)
            .collect(Collectors.toSet());
        return revisions.stream().anyMatch(revision -> !cited.contains(revision));
    }

    /**
     * The paths of the questions a reading skips, unless it reads a newer version: ones the submitter answered,
     * and ones a reading already found a suggestion for. A question asked before but never found is asked again,
     * and so is one whose saved value is blank.
     *
     * @param submission the submission
     * @return the full question paths, empty when nothing is answered yet
     */
    static Set<String> getAnswered(final Submission submission)
    {
        final Set<String> answered = new HashSet<>();
        for (final Answer answer : submission.getAnswers()) {
            // Called once: each call resolves the reference again
            final Question question = answer.getQuestion();
            if (question != null && isAnswered(answer)) {
                answered.add(question.getPath());
            }
        }
        return answered;
    }

    private static boolean isAsking(final Question question, final Set<String> asked)
    {
        return question != null && asked.contains(question.getPath());
    }

    /** The existing answer of each question, by full question path. New runs are written under these. */
    private static Map<String, Answer> getAnswers(final Submission submission)
    {
        final Map<String, Answer> answers = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question != null) {
                answers.putIfAbsent(question.getPath(), answer);
            }
        }
        return answers;
    }

    /** Whether the answer has a non-blank value, or a run that found a suggestion. */
    private static boolean isAnswered(final Answer answer)
    {
        final String[] values = answer.getValue();
        final boolean hasValue = values != null && Arrays.stream(values).anyMatch(value -> !value.isBlank());
        return hasValue || answer.getExtractions().stream().anyMatch(run -> run.getExtractedAnswer() != null);
    }
}
