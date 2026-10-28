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
package io.uhndata.iap.submissions.internal.validators;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerConstraint;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save whose answers break a constraint their question carries, as judged by the registered
 * {@link AnswerConstraint}s: the answer count, the answers offered, the data type of a numeric answer, its bounds, a
 * pattern, and whatever other bundles add.
 *
 * <p>Only the questions the submitter is shown are judged, the same set the completeness marking judges. Falling
 * short of {@code minAnswers} is not judged here: answers arrive one at a time, so a shortfall is incompleteness,
 * not a reason to refuse a save.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerValidator.class)
public class AnswerConstraintsValidator implements AnswerValidator
{
    /**
     * In the order to consult them. The framework keeps a replaced list field sorted by ascending
     * {@code service.ranking}, and re-sorts it as constraints come and go; a field updated in place would not be.
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<AnswerConstraint> constraints;

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        // One snapshot for the whole save, so every question is judged by the same constraints
        final List<AnswerConstraint> judges = this.constraints;
        return ApplicableItems.getQuestions(submission, this.conditions).stream()
            .map(question -> refusal(judges, question, answers.getOrDefault(question.getPath(), List.of())))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    }

    private static Optional<String> refusal(final List<AnswerConstraint> judges, final Question question,
        final List<String> values)
    {
        return judges.stream()
            .map(constraint -> constraint.refusal(question, values))
            .flatMap(Optional::stream)
            .findFirst();
    }
}
