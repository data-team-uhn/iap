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
import java.util.function.Predicate;
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
     * The questions of one requirement the model can be asked (those with an extraction prompt), keyed by
     * {@link ExtractionField#getKey}, in schema order.
     *
     * @param submission the submission whose schema is read
     * @param requirement the requirement to ask about, by name
     * @return the extractable questions, empty when the requirement has none or does not apply
     */
    static Map<String, Question> getExtractable(final Submission submission, final String requirement)
    {
        final String versionPath = submission.getSchemaVersion().getPath();
        // Keep schema order: the model is shown the questions in this order
        final Map<String, Question> questions = new LinkedHashMap<>();
        for (final Question question : submission.getQuestions(requirement)) {
            if (ExtractionField.isExtractable(question)) {
                questions.put(ExtractionField.getKey(versionPath, question), question);
            }
        }
        return questions;
    }

    /**
     * The questions as fields to ask the model. Each field's key is the key its answer comes back under.
     *
     * @param questions the questions to ask, by key
     * @return the fields to ask the model, in the same order
     */
    static List<ExtractionField> toFields(final Map<String, Question> questions)
    {
        final List<ExtractionField> fields = new ArrayList<>(questions.size());
        questions.forEach((key, question) -> fields.add(ExtractionField.of(key, question)));
        return fields;
    }

    /**
     * Saves one run for each entry in {@code results}, found or not, under that question's answer. The intake
     * passes every question whose call went through; the gate passes only its sure picks. An entry whose key is
     * not in {@code questions} is skipped.
     *
     * <p>Each run names the document versions it read, which is how a later reading knows those versions were
     * already read for that question. No answer's value is written: what the model read stays a suggestion until
     * the submitter acts on it.</p>
     *
     * @param resolver the session to write with
     * @param target the submission node
     * @param submission the submission, for the answers it already has
     * @param questions what was asked, by key
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
     * The questions not yet read from a newer document version the step reads: a file that is not its document's
     * first version, and that none of the question's own runs cites. Each question is checked on its own, so a
     * reading where one call failed leaves that call's questions unread, and the next reading asks them again.
     *
     * @param submission the submission
     * @param questions the questions the step asks, answered or not
     * @param files the files the step reads
     * @return the paths of the questions still to read from a newer version, empty when the files are all first
     *     versions
     */
    static Set<String> getUnreadInNewerVersions(final Submission submission, final Collection<Question> questions,
        final List<File> files)
    {
        final Set<String> revisions = files.stream()
            .map(File::getVersion)
            .filter(version -> version != null && version.getNumber() > 1)
            .map(DocumentVersion::getPath)
            .collect(Collectors.toSet());
        if (revisions.isEmpty()) {
            return Set.of();
        }
        final Set<String> asked = questions.stream().map(Question::getPath).collect(Collectors.toSet());
        final Map<String, Set<String>> cited = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            // Called once: each call resolves the reference again
            final Question question = answer.getQuestion();
            if (question != null && asked.contains(question.getPath())) {
                answer.getExtractions().stream()
                    .flatMap(extraction -> extraction.getSources().stream())
                    .forEach(source -> cited.computeIfAbsent(question.getPath(), path -> new HashSet<>())
                        .add(source.getPath()));
            }
        }
        return asked.stream()
            .filter(path -> !cited.getOrDefault(path, Set.of()).containsAll(revisions))
            .collect(Collectors.toSet());
    }

    /**
     * The paths of the questions the submitter answered: the ones the intake skips, unless it reads a newer
     * version. A question with only a suggestion is asked again, and so is one whose saved value is blank.
     *
     * @param submission the submission
     * @return the full question paths, empty when nothing is answered yet
     */
    static Set<String> getAnswered(final Submission submission)
    {
        return getQuestionPaths(submission, ExtractionFields::hasValue);
    }

    /**
     * The paths of the questions the submitter answered or a reading found a suggestion for: the classifications
     * the gate skips, even of a newer version. A pick drives the reading's route, so it is made once.
     *
     * @param submission the submission
     * @return the full question paths, empty when nothing is answered or suggested yet
     */
    static Set<String> getAnsweredOrSuggested(final Submission submission)
    {
        return getQuestionPaths(submission, answer -> hasValue(answer)
            || answer.getExtractions().stream().anyMatch(run -> !run.getExtractedValues().isEmpty()));
    }

    /**
     * Whether the submitter answered the question, or the model was already asked it: the categorisation the
     * upload job skips.
     *
     * @param submission the submission
     * @param question the question
     * @return {@code true} when its answer has a value or a run
     */
    static boolean isAnsweredOrRead(final Submission submission, final Question question)
    {
        return submission.getAnswers().stream().anyMatch(answer -> {
            final Question asked = answer.getQuestion();
            return asked != null && asked.getPath().equals(question.getPath())
                && (hasValue(answer) || !answer.getExtractions().isEmpty());
        });
    }

    private static Set<String> getQuestionPaths(final Submission submission, final Predicate<Answer> chosen)
    {
        final Set<String> paths = new HashSet<>();
        for (final Answer answer : submission.getAnswers()) {
            // Called once: each call resolves the reference again
            final Question question = answer.getQuestion();
            if (question != null && chosen.test(answer)) {
                paths.add(question.getPath());
            }
        }
        return paths;
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

    /** Whether the answer holds a non-blank value: something the submitter saved, not a field clicked past. */
    private static boolean hasValue(final Answer answer)
    {
        final String[] values = answer.getValue();
        return values != null && Arrays.stream(values).anyMatch(value -> !value.isBlank());
    }
}
