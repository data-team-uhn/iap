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
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;

/**
 * Judges a submission's answers. Every question the submitter is shown has an answer, empty until they fill it in,
 * and an answer is incomplete while it holds fewer non-blank values than its question's {@code minAnswers}.
 *
 * <p>A question is shown when it, every section around it and its form requirement all apply. An empty answer to a
 * question that has stopped being shown is removed. One that holds something is kept, so that changing the answer
 * a condition depends on and changing it back loses nothing.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class QuestionCompletenessEvaluator implements CompletenessEvaluator
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public List<Resource> evaluate(final Resource submission) throws PersistenceException
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        final Map<String, List<String>> given = model.getAnswersByQuestion();
        final Map<String, List<Resource>> answers = answers(model, submission.getResourceResolver());
        final List<Question> shown = shown(model);
        final List<Resource> incomplete = new ArrayList<>();
        for (final Question question : shown) {
            final List<Resource> existing = answers.remove(question.getPath());
            final long values = given.getOrDefault(question.getPath(), List.of()).stream()
                .filter(value -> !value.isBlank())
                .count();
            final List<Resource> parts = existing == null ? List.of(create(submission, question)) : existing;
            if (values < question.getMinAnswers()) {
                incomplete.addAll(parts);
            }
        }
        // What is left answers a question no longer shown
        for (final Resource stale : answers.values().stream().flatMap(List::stream).collect(Collectors.toList())) {
            if (isEmpty(stale)) {
                VersioningUtils.checkOut(submission);
                submission.getResourceResolver().delete(stale);
            }
        }
        return incomplete;
    }

    /**
     * The questions the submitter is shown, in schema order.
     *
     * @param submission the submission whose conditions decide
     * @return the questions that apply
     */
    private List<Question> shown(final Submission submission)
    {
        final List<Question> shown = new ArrayList<>();
        submission.getSchemaVersion().getRequirements().stream()
            .filter(FormRequirement.class::isInstance)
            .filter(requirement -> this.conditions.applies(requirement, submission))
            .forEach(form -> ((FormRequirement) form).getChildren()
                .forEach(item -> collect(item, submission, shown)));
        return shown;
    }

    private void collect(final FormItem item, final Submission submission, final List<Question> shown)
    {
        if (!this.conditions.applies(item, submission)) {
            return;
        }
        if (item instanceof Question) {
            shown.add((Question) item);
        } else if (item instanceof Section) {
            ((Section) item).getChildren().forEach(child -> collect(child, submission, shown));
        }
    }

    /**
     * The submission's answers, by the path of the question each one answers. An answer whose question no longer
     * resolves is left out.
     *
     * @param submission the submission
     * @param resolver the session writing it
     * @return the answers' resources, by question path
     */
    private static Map<String, List<Resource>> answers(final Submission submission, final ResourceResolver resolver)
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

    /**
     * Creates the empty answer to a question nobody has answered.
     *
     * @param submission the submission
     * @param question the question
     * @return the answer created
     * @throws PersistenceException when it cannot be written
     */
    private static Resource create(final Resource submission, final Question question) throws PersistenceException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        VersioningUtils.checkOut(submission);
        final Resource answer = resolver.create(submission, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Answer"));
        ReferenceUtils.setReference(answer, "question", Objects.requireNonNull(
            resolver.getResource(question.getPath()), "A question the schema lists can be read"));
        return answer;
    }

    private static boolean isEmpty(final Resource answer)
    {
        return List.of(answer.getValueMap().get("value", new String[0])).stream().allMatch(String::isBlank);
    }
}
