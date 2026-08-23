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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save whose text answer does not match the question's {@code pattern} in full.
 *
 * <p>
 * The refusal is the question's own {@code patternMessage} when it has one, because the pattern itself is readable
 * only by its author; without one the pattern is quoted, which is honest but unhelpful, and schemas meant for
 * people should say what they want. A pattern that cannot even be compiled also refuses the save, naming the
 * schema as the thing to fix: accepting whatever comes while the schema is broken would silently run without the
 * rule the schema states, which is worse than being loud about it.
 * </p>
 *
 * <p>
 * Only the questions currently asked are judged, and blank values are passed over — an emptied field stores
 * nothing, and whether something must be given at all is the answer-count pair's business, not the pattern's.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class PatternValidator implements AnswerValidator
{
    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        return submission.getQuestions().stream()
            .filter(question -> question.getPattern() != null)
            .map(question -> this.refusal(question, answers.getOrDefault(question.getPath(), List.of())))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether any of one question's values fails its pattern.
     *
     * @param question the question to judge
     * @param values the values its answer holds
     * @return a reason to refuse, or {@code null} when every value matches
     */
    private String refusal(final Question question, final List<String> values)
    {
        // Read once into a local: the accessor is @Nullable, and only the single read is guarded by the caller's
        // filter
        final String declared = Objects.requireNonNull(question.getPattern());
        final Pattern pattern;
        try {
            pattern = Pattern.compile(declared);
        } catch (final PatternSyntaxException broken) {
            return "The expected format for \"" + question.getText()
                + "\" cannot be checked; this schema needs fixing.";
        }
        return values.stream()
            .filter(value -> !value.isBlank())
            .filter(value -> !pattern.matcher(value).matches())
            .findFirst()
            .map(value -> this.message(question))
            .orElse(null);
    }

    /**
     * What the submitter is told about a value that does not match.
     *
     * @param question the question whose pattern was missed
     * @return the question's own message, or one quoting the pattern when it has none
     */
    private String message(final Question question)
    {
        return Objects.requireNonNullElseGet(question.getPatternMessage(),
            () -> "The answer to \"" + question.getText() + "\" must match " + question.getPattern() + ".");
    }
}
