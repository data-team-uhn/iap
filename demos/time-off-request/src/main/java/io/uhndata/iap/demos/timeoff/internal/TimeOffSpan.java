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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.DateUtils;

/**
 * The days a request for several days covers, from its first day away to its last.
 *
 * @param first the first day away
 * @param last the last day away
 * @version $Id$
 * @since 0.1.0
 */
record TimeOffSpan(LocalDate first, LocalDate last)
{
    /**
     * The span a request asks for.
     *
     * @param request a submission answering the time off request schema
     * @return the span, or {@code null} unless the request asks for several days and gives both days as dates
     */
    static TimeOffSpan askedFor(final Submission request)
    {
        if (!TimeOffQuestions.MULTIPLE_DAYS_OPTION.equals(request.getAnswerTo(TimeOffQuestions.DURATION_QUESTION))) {
            // Without a span of days, a date left over from an earlier answer means nothing
            return null;
        }
        final LocalDate first = DateUtils.parseDate(request.getAnswerTo(TimeOffQuestions.START_DATE_QUESTION));
        final LocalDate last = DateUtils.parseDate(request.getAnswerTo(TimeOffQuestions.END_DATE_QUESTION));
        return first == null || last == null ? null : new TimeOffSpan(first, last);
    }

    /**
     * Whether the last day comes before the first, which no request can mean.
     *
     * @return {@code true} if the days are the wrong way round
     */
    boolean isBackwards()
    {
        return this.last.isBefore(this.first);
    }

    /**
     * The number of days away, counting both the first and the last.
     *
     * @return the number of days, at least 1 for a span the right way round; zero or less for one given backwards
     */
    long days()
    {
        return ChronoUnit.DAYS.between(this.first, this.last) + 1;
    }
}
