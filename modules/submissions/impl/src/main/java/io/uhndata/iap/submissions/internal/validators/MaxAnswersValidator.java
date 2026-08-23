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

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save giving a question more values than its {@code maxAnswers} takes.
 *
 * <p>The maximum is the half of the answer-count pair that is an error rather than incompleteness. No form offers a
 * way to exceed it: a single-valued control holds one value, and a capped list of checkboxes stops offering. So a
 * payload that does was not a submitter filling things in. Falling short of {@code minAnswers} is not judged here,
 * since answers arrive one at a time.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerValidator.class)
public class MaxAnswersValidator implements AnswerValidator, QuestionRule
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public String validate(final Submission submission, final String actor)
    {
        return firstRefusalIn(submission, this.conditions);
    }

    @Override
    public boolean constrains(final Question question)
    {
        // Zero or a negative maximum means the question takes any number of values
        return question.getMaxAnswers() > 0;
    }

    @Override
    public String refusal(final Question question, final List<String> values)
    {
        if (values.size() <= question.getMaxAnswers()) {
            return null;
        }
        // Numbers as labelled values rather than in a sentence: a count inside one needs plural agreement, which is
        // the one thing a translator cannot fix
        return "Too many values for \"" + question.getText() + "\". Given: " + values.size() + ". Allowed: "
            + question.getMaxAnswers() + ".";
    }
}
