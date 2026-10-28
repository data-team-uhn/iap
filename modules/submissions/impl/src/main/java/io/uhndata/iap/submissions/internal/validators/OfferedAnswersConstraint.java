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
import java.util.Set;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.spi.AnswerConstraint;

/**
 * Refuses a value a question offering a fixed set of answers does not offer. A question offering none is answered
 * freely, in whatever its data type accepts.
 *
 * <p>The form offers only those answers, so a value from outside them did not come from it. And an option's value
 * is what conditions compare against: a stray one would make the request go on to ask questions nobody chose.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerConstraint.class, property = "service.ranking:Integer=15")
public final class OfferedAnswersConstraint implements AnswerConstraint
{
    @Override
    public Optional<String> refusal(final Question question, final List<String> values)
    {
        final Set<String> offered =
            question.getOptions().stream().map(AnswerOption::getValue).collect(Collectors.toSet());
        if (offered.isEmpty()) {
            return Optional.empty();
        }
        return values.stream()
            .filter(value -> !offered.contains(value))
            .findFirst()
            .map(value -> "\"" + value + "\" is not one of the answers \"" + question.getText() + "\" offers.");
    }
}
