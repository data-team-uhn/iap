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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a request for more time off than the requester has left.
 *
 * <p>
 * The balance is the one {@link TimeOffBudgetHandler} looked up when the request was raised and recorded on it, not
 * a fresh call: the number that refuses a save is then exactly the number the approver sees later, and the demo's
 * stand-in for a holiday bank is asked once per request rather than once per keystroke.
 * </p>
 *
 * <p>
 * It judges only what it can. Answers are saved one at a time, so a request is half-answered for most of its life:
 * until the duration says how to count, and — for a span — until both dates are given, there is no number to
 * compare and the save goes through. Refusing an incomplete request would make it impossible to finish filling in.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TimeOffBudgetValidator implements AnswerValidator
{
    /** The question saying how the absence is counted, and the answers it offers. */
    private static final String DURATION = "duration";

    private static final String HALF_DAY = "half-day";

    private static final String FULL_DAY = "full-day";

    private static final String MULTIPLE_DAYS = "multiple-days";

    private static final String START_DATE = "startDate";

    private static final String END_DATE = "endDate";

    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Double requested = requestedDays(submission);
        if (requested == null) {
            return null;
        }
        final Long remaining = submission.get(TimeOffBudgetHandler.REMAINING_DAYS, Long.class);
        if (remaining == null) {
            // The workflow looks the balance up as its first step, so this is a request whose process has not
            // started rather than one to refuse
            return null;
        }
        if (requested > remaining) {
            // Numbers as labelled values rather than in a sentence: a count inside one needs plural agreement,
            // which is the one thing a translator cannot fix (see the ReferrerReport note in docs/deletion.md)
            return "This asks for more time off than you have left. Requested: " + plain(requested)
                + ". Remaining: " + remaining + ".";
        }
        return null;
    }

    /**
     * How many days the request asks for, or {@code null} while that cannot be told.
     *
     * @param submission the request being answered
     * @return a number of days, or {@code null} when the answers do not yet say
     */
    private Double requestedDays(final Submission submission)
    {
        final Map<String, String> answers = answersByQuestion(submission);
        final String duration = answers.get(DURATION);
        if (HALF_DAY.equals(duration)) {
            return 0.5;
        }
        if (FULL_DAY.equals(duration)) {
            return 1.0;
        }
        if (!MULTIPLE_DAYS.equals(duration)) {
            // Not answered, or answered with something this rule does not know how to count
            return null;
        }
        return span(answers.get(START_DATE), answers.get(END_DATE));
    }

    /**
     * The number of days a stay covers, counting both ends, or {@code null} when the two dates do not describe one.
     */
    private Double span(final String from, final String to)
    {
        if (from == null || to == null) {
            return null;
        }
        try {
            final long days = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1;
            // A return date before the departure is a request to correct, not one to price
            return days > 0 ? (double) days : null;
        } catch (final DateTimeParseException e) {
            // A date that does not parse is not this rule's to complain about
            return null;
        }
    }

    /**
     * The submission's answers, by the name of the question each one answers.
     */
    private Map<String, String> answersByQuestion(final Submission submission)
    {
        // A loop rather than a stream: the question is read once into a local, which is what keeps the null check
        // and the dereference talking about the same object
        final Map<String, String> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            final String[] values = answer.getValue();
            if (question != null && values.length > 0) {
                byQuestion.put(question.getName(), values[0]);
            }
        }
        return byQuestion;
    }

    /**
     * A day count without the trailing zero a whole number would otherwise carry.
     */
    private String plain(final double days)
    {
        return days == Math.floor(days) ? String.valueOf((long) days) : String.valueOf(days);
    }
}
