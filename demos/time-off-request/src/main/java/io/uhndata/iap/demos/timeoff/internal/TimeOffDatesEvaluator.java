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
package io.uhndata.iap.demos.timeoff.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;

/**
 * Holds back a request whose last day away comes before its first.
 *
 * <p>The request stays incomplete, so it cannot be sent, until the days are put the right way round. Refusing the
 * save instead would trap a requester who moves a span later one day at a time. Moving the first day past the old
 * last day reads as backwards, and moving the last day first can ask for more than the budget allows.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class TimeOffDatesEvaluator implements CompletenessEvaluator
{
    @Override
    public List<Resource> evaluate(final Resource submission)
    {
        final Submission request = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        if (request.get(TimeOffBudgetHandler.REMAINING_DAYS_PROPERTY, Long.class) == null) {
            // Not a time off request
            return List.of();
        }
        final TimeOffSpan span = TimeOffSpan.askedFor(request);
        if (span == null || !span.isBackwards()) {
            return List.of();
        }
        final String lastDay = request.getSchemaVersion().getPath() + "/" + TimeOffQuestions.END_DATE_QUESTION;
        return request.getAnswers().stream()
            .filter(answer -> answers(answer, lastDay))
            .map(answer -> submission.getResourceResolver().getResource(answer.getPath()))
            .filter(Objects::nonNull)
            .toList();
    }

    private static boolean answers(final Answer answer, final String questionPath)
    {
        return Optional.ofNullable(answer.getQuestion()).map(Question::getPath).filter(questionPath::equals)
            .isPresent();
    }
}
