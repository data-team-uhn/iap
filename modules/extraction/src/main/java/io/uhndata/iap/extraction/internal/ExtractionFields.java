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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Which questions a step asks of a document, and what becomes of the answers.
 *
 * <p>Shared by every {@code intakeAnswers} step, so a schema read in stages is asked the same way and written
 * the same way in every stage.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionFields
{
    private ExtractionFields()
    {
        // Utility
    }

    /**
     * The questions worth putting to the model, by their path within the schema version.
     *
     * @param submission whose schema is being read
     * @param requirement the requirement to ask about, or {@code null} for every question the schema asks
     * @return the extractable questions, empty when the requirement is not being asked
     */
    static Map<String, Question> extractable(final Submission submission, final String requirement)
    {
        final String versionPath = submission.getSchemaVersion().getPath();
        // Kept in the order the schema asks them, which is the order the model is shown them in
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
     * The questions as the model is asked them. The key is the path within the schema version, which is what
     * the answers come back under and what they are written back by.
     *
     * @param questions the questions to ask, by path within the schema version
     * @return the fields to put to a model
     */
    static List<ExtractionField> toFields(final Map<String, Question> questions)
    {
        final List<ExtractionField> fields = new ArrayList<>(questions.size());
        questions.forEach((key, question) -> fields.add(ExtractionField.of(question, key)));
        return fields;
    }

    /**
     * Record every answer the model found, for the questions that carry no answer yet.
     *
     * @param resolver the session to write with
     * @param target the submission node
     * @param submission the submission, for what it already holds
     * @param questions what was asked, by path within the schema version
     * @param results what the model answered, keyed the same way
     * @param files the documents the answers were read from
     * @throws PersistenceException if the answers cannot be written
     */
    static void write(final ResourceResolver resolver, final Resource target, final Submission submission,
        final Map<String, Question> questions, final Map<String, FieldResult> results, final List<File> files)
        throws PersistenceException
    {
        final Set<String> answered = answered(submission);
        for (final Map.Entry<String, FieldResult> entry : results.entrySet()) {
            final Question question = questions.get(entry.getKey());
            if (entry.getValue().found() && question != null && !answered.contains(question.getPath())) {
                ExtractedAnswers.write(resolver, target, question, entry.getValue(), files);
            }
        }
    }

    /**
     * The paths of the questions the submission already holds an answer for.
     */
    private static Set<String> answered(final Submission submission)
    {
        final Set<String> answered = new HashSet<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question != null) {
                answered.add(question.getPath());
            }
        }
        return answered;
    }
}
