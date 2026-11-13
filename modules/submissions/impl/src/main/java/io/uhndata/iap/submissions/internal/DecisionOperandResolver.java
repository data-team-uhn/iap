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
 * Resolves {@code decision} operands. A decision operand gives the answer to a question. If the submitter gave a
 * value, it uses that value. If not, it uses the suggestion of the newest reading.
 *
 * <p>A reading never saves its result in the answer value. It only adds a suggestion. Some workflow steps must act
 * before any person has checked the suggestion. For example, a step that checks if a document is a proposal. These
 * steps use this operand to get the suggested value.</p>
 *
 * <p>Name the question the same way as in an {@code answer} operand.</p>
 *
 * <p>Use this only in workflow steps. Form conditions must use {@code answer}. This way, the form the submitter sees
 * does not change because of a suggestion they did not accept.</p>
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
