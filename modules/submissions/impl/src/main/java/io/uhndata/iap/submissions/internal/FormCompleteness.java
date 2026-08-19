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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Whether a submission has been answered as far as its schema asks: every question marked {@code required} that
 * currently applies has an answer.
 *
 * <p><strong>Conditions are why this cannot be decided anywhere else.</strong> A required question inside a section
 * that does not apply is not missing — nobody was ever asked it — so completeness has to be judged against the same
 * resolved form the submitter is shown, by the same {@link ConditionEvaluator}. That is also why this sits beside
 * {@link SubmissionFormServlet} and shares its answer index with it: two walks that could disagree about which
 * questions apply would show a form with nothing left to answer and refuse to accept it.</p>
 *
 * <p>Only a {@code sch:FormRequirement} is judged. A document to provide or an approval to obtain is something a
 * submitter cannot discharge by answering, and {@code required} is a property of a question.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FormCompleteness
{
    private final ConditionEvaluator conditions;

    /**
     * Constructor.
     *
     * @param conditions the evaluator deciding which parts of the schema currently apply
     */
    FormCompleteness(final ConditionEvaluator conditions)
    {
        this.conditions = conditions;
    }

    /**
     * Whether anything this submission is asked for is still unanswered.
     *
     * @param submission the submission to judge
     * @return {@code true} if a required question that applies has no answer
     */
    boolean isIncomplete(final Submission submission)
    {
        // An assertion rather than a case, the way the form projection treats it: sub:Submission makes the
        // reference mandatory, so a submission without one cannot be committed in the first place
        final SchemaVersion version = Objects.requireNonNull(submission.getSchemaVersion(),
            "A submission always points at the schema version it answers");
        final Map<String, List<String>> answers = answersByQuestion(submission);
        return version.getRequirements().stream()
            .filter(requirement -> this.conditions.applies(requirement, submission))
            .anyMatch(requirement -> incomplete(requirement, submission, answers));
    }

    /**
     * Whether one requirement is still missing an answer it asks for.
     *
     * @param requirement the requirement to judge
     * @param submission the submission it belongs to
     * @param answers the submission's answers, by question path
     * @return {@code true} if it holds a required question that applies and is unanswered
     */
    private boolean incomplete(final Requirement requirement, final Submission submission,
        final Map<String, List<String>> answers)
    {
        return requirement instanceof FormRequirement
            && missing(((FormRequirement) requirement).getChildren(), submission, answers);
    }

    /**
     * Whether any of these items, or anything nested in them, is a required question left unanswered.
     *
     * @param items the form items to walk
     * @param submission the submission they are being judged against
     * @param answers the submission's answers, by question path
     * @return {@code true} if something required and applicable is unanswered
     */
    private boolean missing(final List<FormItem> items, final Submission submission,
        final Map<String, List<String>> answers)
    {
        return items.stream()
            .filter(item -> this.conditions.applies(item, submission))
            .anyMatch(item -> item instanceof Section
                ? missing(((Section) item).getChildren(), submission, answers)
                : item instanceof Question && unanswered((Question) item, answers));
    }

    /**
     * Whether a question that had to be answered has not been.
     *
     * <p>A blank answer does not count as one, which matters because clearing a field posts an empty value rather
     * than removing the answer: the node stays, holding {@code ""}, and treating that as answered would let a
     * required question be satisfied by emptying it.</p>
     *
     * @param question the question to check
     * @param answers the submission's answers, by question path
     * @return {@code true} if the question is required and holds no non-blank value
     */
    private static boolean unanswered(final Question question, final Map<String, List<String>> answers)
    {
        // allMatch on an empty list is true, which is the wanted answer for a question with no answer node at all
        return question.isRequired()
            && answers.getOrDefault(question.getPath(), List.of()).stream().allMatch(String::isBlank);
    }

    /**
     * A submission's answers, by the path of the question each answers.
     *
     * @param submission the submission to index
     * @return the answers' values, by question path
     */
    static Map<String, List<String>> answersByQuestion(final Submission submission)
    {
        // A loop rather than a stream on purpose: the question has to be read once into a local — asking twice
        // around a null check is what makes a @Nullable accessor look safe to dereference — and collecting to a
        // map would need a merge function for a collision that only degenerate content can produce, which is a
        // branch nothing would ever cover.
        final Map<String, List<String>> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            // An answer whose question no longer resolves is the answer to nothing being asked now
            if (question != null) {
                // The value is nullable and List.of would throw on a null array: an answer node carrying no value
                // at all is permitted by the node type, and it means the same as one carrying nothing
                byQuestion.putIfAbsent(question.getPath(),
                    List.of(Objects.requireNonNullElse(answer.getValue(), new String[0])));
            }
        }
        return byQuestion;
    }
}
