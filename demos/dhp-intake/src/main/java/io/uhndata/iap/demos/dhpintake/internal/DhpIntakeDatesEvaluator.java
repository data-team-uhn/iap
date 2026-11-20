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
package io.uhndata.iap.demos.dhpintake.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;
import io.uhndata.iap.utils.DateUtils;

/**
 * Holds back an intake request whose dates cannot be right: a start date in the future, an end date before the
 * start date, or a repeating request that stops in a month already over.
 *
 * <p>The request stays incomplete, so it cannot be sent, and the answer at fault is pointed at. A schema without
 * these questions has no answers at their paths, so it is never held back.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class DhpIntakeDatesEvaluator implements CompletenessEvaluator
{
    /** The path of the question asking for the earliest date the data covers. */
    static final String START_DATE_QUESTION = "intake/timePeriod/startDate";

    /** The path of the question asking for the latest date the data covers. */
    static final String END_DATE_QUESTION = "intake/timePeriod/endDate";

    /** The path of the question asking whether the data is needed once or on a schedule. */
    static final String FREQUENCY_QUESTION = "intake/timePeriod/frequency";

    /** The value the frequency question stores for data sent on a schedule. */
    static final String REPEATING_OPTION = "repeating";

    /** The path of the question asking, as {@code YYYY-MM}, when repeating data stops being needed. */
    static final String STOP_MONTH_QUESTION = "intake/timePeriod/recurring/stopMonth";

    @Override
    public List<Resource> evaluate(final Resource submission)
    {
        final Submission request = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");

        // Name the questions whose answers break a rule
        final LocalDate today = LocalDate.now();
        final LocalDate first = DateUtils.parseDate(request.getAnswerTo(START_DATE_QUESTION));
        final LocalDate last = DateUtils.parseDate(request.getAnswerTo(END_DATE_QUESTION));
        final List<String> wrong = new ArrayList<>();
        if (first != null && first.isAfter(today)) {
            wrong.add(START_DATE_QUESTION);
        }
        if (first != null && last != null && last.isBefore(first)) {
            wrong.add(END_DATE_QUESTION);
        }
        // Without a schedule, a month left over from an earlier answer means nothing
        if (REPEATING_OPTION.equals(request.getAnswerTo(FREQUENCY_QUESTION))
            && hasEnded(request.getAnswerTo(STOP_MONTH_QUESTION), today)) {
            wrong.add(STOP_MONTH_QUESTION);
        }
        if (wrong.isEmpty()) {
            return List.of();
        }

        // Point at the answers to those questions
        final String versionPath = request.getSchemaVersion().getPath() + "/";
        final List<String> questionPaths = wrong.stream().map(versionPath::concat).toList();
        return request.getAnswers().stream()
            .filter(answer -> Optional.ofNullable(answer.getQuestion()).map(Question::getPath)
                .filter(questionPaths::contains).isPresent())
            .map(answer -> submission.getResourceResolver().getResource(answer.getPath()))
            .filter(Objects::nonNull)
            .toList();
    }

    // The current month has not ended, so a request may stop needing data this month
    private static boolean hasEnded(final String month, final LocalDate today)
    {
        if (month == null) {
            return false;
        }
        try {
            return YearMonth.parse(month).isBefore(YearMonth.from(today));
        } catch (final DateTimeParseException e) {
            // The question's pattern refuses such a value before it is saved
            return false;
        }
    }
}
