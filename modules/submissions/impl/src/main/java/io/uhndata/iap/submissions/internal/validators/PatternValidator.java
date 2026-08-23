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
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save whose text answer does not match the question's {@code pattern} in full.
 *
 * <p>The refusal is the question's own {@code patternMessage} when it has one, because only its author can read the
 * pattern. Without one, the pattern is quoted. A pattern that cannot be compiled also refuses the save, naming the
 * schema as the thing to fix. Accepting anything while the schema is broken would drop the rule it states.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerValidator.class)
public class PatternValidator implements AnswerValidator, QuestionRule
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
        return question.getPattern() != null;
    }

    @Override
    public String refusal(final Question question, final List<String> values)
    {
        final Pattern pattern;
        try {
            pattern = Pattern.compile(Objects.requireNonNull(question.getPattern(), "Only a pattern is judged"));
        } catch (final PatternSyntaxException broken) {
            return "The expected format for \"" + question.getText()
                + "\" cannot be checked; this schema needs fixing.";
        }
        return values.stream().allMatch(value -> pattern.matcher(value).matches()) ? null : message(question);
    }

    /**
     * What the submitter is told about a value that does not match.
     *
     * @param question the question whose pattern was missed
     * @return the question's own message, or one quoting the pattern when it has none
     */
    private static String message(final Question question)
    {
        return Objects.requireNonNullElseGet(question.getPatternMessage(),
            () -> "The answer to \"" + question.getText() + "\" must match " + question.getPattern() + ".");
    }
}
