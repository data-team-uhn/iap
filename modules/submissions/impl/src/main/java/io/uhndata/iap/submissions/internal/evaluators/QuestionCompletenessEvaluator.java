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
package io.uhndata.iap.submissions.internal.evaluators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;

/**
 * Judges a submission's answers. An answer to a question the submitter is shown is incomplete while it holds fewer
 * non-blank values than its question's {@code minAnswers}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class QuestionCompletenessEvaluator implements CompletenessEvaluator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QuestionCompletenessEvaluator.class);

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public List<Resource> evaluate(final Resource submission)
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        final Map<String, List<String>> given = model.getAnswersByQuestion();
        final Map<String, List<Resource>> answers = getExistingAnswers(model, submission.getResourceResolver());
        final List<Resource> incomplete = new ArrayList<>();
        for (final Question question : ApplicableItems.getQuestions(model, this.conditions)) {
            final List<Resource> existing = answers.get(question.getPath());
            final long values = given.getOrDefault(question.getPath(), List.of()).stream()
                .filter(value -> !value.isBlank())
                .count();
            if (existing == null) {
                LOGGER.warn("{} has no answer to {}, it should have been automatically created",
                    submission.getPath(), question.getPath());
                // A problem rather than a failure: a part creator left an item without its part
                ErrorLogger.logProblem("A submission has no answer to a question",
                    ErrorContext.of(QuestionCompletenessEvaluator.class, "evaluate")
                        .about(submission.getPath())
                        .with("question", question.getPath()));
            }
            if (values < question.getMinAnswers()) {
                incomplete.addAll(existing == null ? List.of(submission) : existing);
            }
        }
        return incomplete;
    }

    /**
     * The submission's answers, by the path of the question each one answers. An answer whose question no longer
     * resolves is left out.
     *
     * @param submission the submission
     * @param resolver the session reading it
     * @return the answers' resources, by question path
     */
    private static Map<String, List<Resource>> getExistingAnswers(final Submission submission,
        final ResourceResolver resolver)
    {
        final Map<String, List<Resource>> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            final Resource resource = resolver.getResource(answer.getPath());
            if (question != null && resource != null) {
                byQuestion.computeIfAbsent(question.getPath(), path -> new ArrayList<>()).add(resource);
            }
        }
        return byQuestion;
    }
}
