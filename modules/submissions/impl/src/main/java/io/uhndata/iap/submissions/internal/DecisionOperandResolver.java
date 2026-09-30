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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Resolves {@code decision} operands: a question's answer, but only once it can be acted on.
 *
 * <p>An answer counts when the submitter gave it, confirmed it or changed it, or when the model was sure enough of
 * it: at or above the threshold of the classification requirement the question belongs to, or the default
 * threshold for any other question. A pick the model was unsure of resolves as no answer, so a condition waits for
 * a person instead of acting on a guess.</p>
 *
 * <p>The operand names the question by its path within the submission's schema version, e.g.
 * {@code is_proposal/decision}. The path is read against the submission the condition is evaluated for, found by
 * walking up from the context, so the same operand works in a schema's own conditions and in a workflow gateway,
 * whose conditions live in the workflow definition rather than in the schema.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class DecisionOperandResolver implements OperandResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionOperandResolver.class);

    @Override
    public String getSource()
    {
        return "decision";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            LOGGER.warn("Decision operand at {} does not name a question", operand.getPath());
            return Operand.EMPTY;
        }
        final Submission submission = findSubmission(context);
        if (submission == null) {
            return Operand.EMPTY;
        }
        final Question question = submission.getSchemaVersion().getChild(value[0], Question.class);
        if (question == null) {
            LOGGER.warn("Decision operand at {} names unknown question {}", operand.getPath(), value[0]);
            return Operand.EMPTY;
        }
        final OperandType type = OperandType.parse(question.getDataType());
        for (final Answer answer : submission.getAnswers()) {
            final Question answered = answer.getQuestion();
            if (answered != null && question.getPath().equals(answered.getPath())) {
                return Operand.of(isSettled(answer, question) ? answer.getValue() : null, type);
            }
        }
        return Operand.of(null, type);
    }

    /** The submission a context is part of, or {@code null} when it is part of none. */
    private static Submission findSubmission(final Content context)
    {
        Content scope = context;
        while (scope != null && !scope.isOfType(Submission.RESOURCE_TYPE)) {
            scope = scope.getParent();
        }
        return scope == null ? null : scope.as(Submission.class);
    }

    /**
     * Whether an answer can be acted on: given, confirmed or changed by the submitter, or picked by a model that
     * was sure enough.
     *
     * @param answer the answer
     * @param question what it answers, for the threshold
     * @return {@code true} when a condition may use it
     */
    private static boolean isSettled(final Answer answer, final Question question)
    {
        final List<Extraction> extractions = answer.getExtractions();
        final List<String> suggested = answer.getSuggestedValues();
        if (extractions.isEmpty() || suggested.isEmpty() || extractions.stream().anyMatch(Extraction::isReviewed)) {
            return true;
        }
        final String[] value = answer.getValue();
        if (value == null || !Arrays.asList(value).equals(suggested)) {
            return true;
        }
        final Double confidence = Objects.requireNonNull(answer.getSurestExtraction(),
            "An answer with extractions always has a surest one").getConfidence();
        return confidence != null && confidence >= getThreshold(question);
    }

    /** The confidence a model's pick needs to count on its own for this question. */
    private static double getThreshold(final Question question)
    {
        // Walks up, since the question may sit in a section of the classification
        for (Content parent = question.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.isOfType(ClassificationRequirement.RESOURCE_TYPE)) {
                return Objects.requireNonNull(parent.as(ClassificationRequirement.class),
                    "A classification requirement node always reads as one").getConfidenceThreshold();
            }
        }
        return ClassificationRequirement.DEFAULT_CONFIDENCE_THRESHOLD;
    }
}
