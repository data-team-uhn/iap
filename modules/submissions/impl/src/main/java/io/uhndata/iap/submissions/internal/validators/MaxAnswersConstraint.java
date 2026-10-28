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
import java.util.Optional;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.spi.AnswerConstraint;

/**
 * Refuses more values than a question's {@code maxAnswers} takes. Zero or a negative maximum takes any number.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerConstraint.class, property = "service.ranking:Integer=10")
public final class MaxAnswersConstraint implements AnswerConstraint
{
    @Override
    public Optional<String> refusal(final Question question, final List<String> values)
    {
        final long maximum = question.getMaxAnswers();
        if (maximum <= 0 || values.size() <= maximum) {
            return Optional.empty();
        }
        // Numbers as labelled values rather than in a sentence, which would need plural agreement
        return Optional.of("Too many values for \"" + question.getText() + "\". Given: " + values.size()
            + ". Allowed: " + maximum + ".");
    }
}
