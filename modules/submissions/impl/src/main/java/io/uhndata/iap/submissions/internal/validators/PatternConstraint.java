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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.spi.AnswerConstraint;

/**
 * Refuses a value that does not match a question's {@code pattern} in full.
 *
 * <p>The refusal is the question's own {@code patternMessage} when it has one, because only its author can read the
 * pattern. Without one, the pattern is quoted. A pattern that cannot be compiled refuses the save too, since
 * accepting anything would drop the rule the schema states, and it is recorded for whoever can fix the schema.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerConstraint.class, property = "service.ranking:Integer=40")
public final class PatternConstraint implements AnswerConstraint
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternConstraint.class);

    @Override
    public Optional<String> refusal(final Question question, final List<String> values)
    {
        final String declared = question.getPattern();
        if (declared == null) {
            return Optional.empty();
        }
        final Pattern pattern;
        try {
            pattern = Pattern.compile(declared);
        } catch (final PatternSyntaxException broken) {
            LOGGER.warn("The pattern of {} does not compile: {}", question.getPath(), broken.getMessage());
            ErrorLogger.logProblem("A question's pattern does not compile",
                ErrorContext.of(PatternConstraint.class, "refusal").about(question.getPath()));
            return Optional.of("The expected format for \"" + question.getText()
                + "\" cannot be checked; this schema needs fixing.");
        }
        return values.stream().allMatch(value -> pattern.matcher(value).matches())
            ? Optional.empty()
            : Optional.of(message(question, declared));
    }

    /**
     * What the submitter is told about a value that does not match.
     *
     * @param question the question whose pattern was missed
     * @param declared its pattern
     * @return the question's own message, or one quoting the pattern when it has none
     */
    private static String message(final Question question, final String declared)
    {
        final String own = question.getPatternMessage();
        return own != null ? own : "The answer to \"" + question.getText() + "\" must match " + declared + ".";
    }
}
