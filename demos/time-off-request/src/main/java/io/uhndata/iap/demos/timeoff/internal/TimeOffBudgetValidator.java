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

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a request for more time off than the requester has left.
 *
 * <p>
 * The balance is the one {@link TimeOffBudgetHandler} recorded on the request when it was raised, not a fresh
 * lookup. A submission without that balance is not a time off request, and is left alone.
 * </p>
 *
 * <p>
 * It judges only what it can. Answers are saved one at a time, so a request is half-answered for most of its life.
 * There is no number to compare until the duration is known, and for a span until both days are given the right way
 * round. Such a save goes through, or the request could never be finished. {@link TimeOffDatesEvaluator} keeps a
 * span given backwards from being sent.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TimeOffBudgetValidator implements AnswerValidator
{
    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Long remaining = submission.get(TimeOffBudgetHandler.REMAINING_DAYS_PROPERTY, Long.class);
        if (remaining == null) {
            return null;
        }
        final Double requested = requestedDays(submission);
        if (requested == null || requested <= remaining) {
            return null;
        }
        // Labelled numbers rather than a sentence: a count inside a sentence needs plural agreement, which a
        // translated message cannot supply
        return "This asks for more time off than you have left. Requested: " + plain(requested)
            + ". Remaining: " + remaining + ".";
    }

    /**
     * How many days the request asks for, or {@code null} while that cannot be told.
     *
     * @param submission the request being answered
     * @return a number of days, {@code 0.5} for half a day; {@code null} if the duration is unanswered or unknown, or
     *     a span of days lacks a readable first or last day, or runs backwards
     */
    private Double requestedDays(final Submission submission)
    {
        final String duration = submission.getAnswerTo(TimeOffQuestions.DURATION_QUESTION);
        if (TimeOffQuestions.HALF_DAY_OPTION.equals(duration)) {
            return 0.5;
        }
        if (TimeOffQuestions.FULL_DAY_OPTION.equals(duration)) {
            return 1.0;
        }
        final TimeOffSpan span = TimeOffSpan.askedFor(submission);
        return span == null || span.isBackwards() ? null : (double) span.days();
    }

    /**
     * A day count without the trailing zero a whole number would otherwise carry.
     */
    private String plain(final double days)
    {
        return days == Math.floor(days) ? String.valueOf((long) days) : String.valueOf(days);
    }
}
