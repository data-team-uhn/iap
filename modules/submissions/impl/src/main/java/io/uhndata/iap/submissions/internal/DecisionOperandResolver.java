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

import java.util.Objects;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.submissions.models.Answer;

/**
 * Resolves {@code decision} operands: what answers a question for a step that acts on a reading before anybody has
 * looked at it. That is the submitter's value, or failing it, what a reading suggested. A reading never writes into
 * an answer's value, so a workflow routing on whether a document is a proposal reads its pick through this. The
 * question is named the same way an {@code answer} operand names it.
 *
 * <p>Only for such steps. A form's own conditions use {@code answer}, so nothing the submitter sees changes on a
 * suggestion they have not accepted.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = OperandResolver.class)
public class DecisionOperandResolver extends AnswerOperandResolver
{
    /** The source of an operand reading the submitter's value, or else the suggestion. */
    public static final String DECISION_SOURCE = "decision";

    @Override
    public String getSource()
    {
        return DECISION_SOURCE;
    }

    @Override
    protected Object readValues(final Content answer)
    {
        return Objects.requireNonNull(answer.as(Answer.class), "An answer node reads as an answer")
            .getValueOrSuggestion().toArray(new String[0]);
    }
}
