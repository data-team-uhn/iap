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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * The forms the main extraction reads, in schema order, each with the documents it names and the questions still
 * open. A form is read only when it applies and names its documents. A question is open while nobody answered it
 * and the model was never asked it; a category question is left to the categorisation.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FormsToRead
{
    private FormsToRead()
    {
        // Utility
    }

    /**
     * One form to read.
     *
     * @param form the form requirement
     * @param files the parsed uploads it names, in the order named
     * @param labels the label of each upload's document requirement, in the same order
     * @param questions its open questions, keyed by {@link ExtractionField#getKey}, in schema order
     * @version $Id$
     * @since 0.1.0
     */
    record FormToRead(FormRequirement form, List<File> files, List<String> labels, Map<String, Question> questions)
    {
    }

    /**
     * The forms that have open questions and at least one parsed upload to read them from.
     *
     * @param submission the submission
     * @return the forms to read, in schema order; empty when there is nothing to read
     */
    static List<FormToRead> getFormsToRead(final Submission submission)
    {
        final Set<String> categories = getCategoryQuestions(submission);
        return getReadableForms(submission).stream()
            .map(form -> {
                final List<String> parsed = form.getDocumentRequirementNames().stream()
                    .filter(name -> isParsed(SubmissionFiles.getCurrentFile(submission, name)))
                    .toList();
                final List<File> files = parsed.stream()
                    .map(name -> SubmissionFiles.getCurrentFile(submission, name))
                    .toList();
                final List<String> labels = parsed.stream()
                    .map(name -> SubmissionFiles.findDocumentRequirement(submission, name))
                    .map(DocumentRequirement::getLabel)
                    .toList();
                return new FormToRead(form, files, labels, getOpenQuestions(submission, form, files, categories));
            })
            .filter(reading -> !reading.files().isEmpty() && !reading.questions().isEmpty())
            .toList();
    }

    /**
     * Why the reading cannot start: a form with open questions names a document that should be there and cannot be
     * read.
     *
     * @param submission the submission
     * @return the message for the submitter, or {@code null} when every document a form needs can be read
     */
    static String findReasonNotToStart(final Submission submission)
    {
        final Set<String> categories = getCategoryQuestions(submission);
        return getReadableForms(submission).stream()
            .filter(form -> !getOpenQuestions(submission, form, List.of(), categories).isEmpty())
            .flatMap(form -> form.getDocumentRequirementNames().stream())
            .filter(name -> SubmissionFiles.isDocumentMissing(submission, name))
            .map(name -> SubmissionFiles.findDocumentRequirement(submission, name))
            .filter(Objects::nonNull)
            .map(DocumentRequirement::getLabel)
            .map(SubmissionFiles::describeMissingDocument)
            .findFirst()
            .orElse(null);
    }

    private static boolean isParsed(final File file)
    {
        return file != null && ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus());
    }

    /** The forms that apply and name the documents they read, in schema order. */
    private static List<FormRequirement> getReadableForms(final Submission submission)
    {
        return submission.getSchemaVersion().getFormRequirements().stream()
            .filter(form -> !form.getDocumentRequirementNames().isEmpty() && submission.isApplicable(form))
            .toList();
    }

    /**
     * The form's questions with an extraction prompt that nobody answered and the model was never asked. Until
     * re-uploads are decided, a question not yet read from a newer version of a document the form reads is open
     * too.
     */
    private static Map<String, Question> getOpenQuestions(final Submission submission, final FormRequirement form,
        final List<File> files, final Set<String> categories)
    {
        final Map<String, Question> questions = new LinkedHashMap<>(
            ExtractionFields.getExtractable(submission, form.getName()));
        questions.values().removeIf(question -> categories.contains(question.getPath()));
        final Set<String> unread = ExtractionFields.getUnreadInNewerVersions(submission, questions.values(), files);
        questions.values().removeIf(question -> ExtractionFields.isAnsweredOrRead(submission, question)
            && !unread.contains(question.getPath()));
        return questions;
    }

    /** The paths of the questions the schema's classifications fill. */
    private static Set<String> getCategoryQuestions(final Submission submission)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(ClassificationRequirement.class::isInstance)
            .map(requirement -> ((ClassificationRequirement) requirement).getQuestion())
            .filter(Objects::nonNull)
            .map(Question::getPath)
            .collect(Collectors.toSet());
    }
}
