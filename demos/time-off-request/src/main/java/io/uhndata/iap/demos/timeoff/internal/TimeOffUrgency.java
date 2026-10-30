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
import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.DateUtils;

/**
 * Whether a time off request is about to start, and saying so on the request.
 *
 * <p>Time off that begins no later than tomorrow is flagged. Whoever decides it has hours rather than days, and the
 * flag picks it out of a list. The notice period is the demo's own rule, so it lives here and not in the
 * platform.</p>
 *
 * <p>The decision is a pure function of the request and a date. It is asked when a request is sent and again every
 * night, and the two must not disagree. The date is passed in, so a test can choose it.</p>
 *
 * <p>Only a request waiting for its decision is judged. Before it is sent nobody has been asked to decide it, and
 * once it is decided nobody is waiting on it any more.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TimeOffUrgency
{
    /** The name of the tag a request about to start carries. */
    static final String URGENT_TAG = "urgent";

    /** The platform's lifecycle tag for a request that has been sent and not yet decided. */
    static final String SUBMITTED_TAG = "submitted";

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeOffUrgency.class);

    private TimeOffUrgency()
    {
        // Utility class
    }

    /**
     * Places or removes the urgency flag, according to what the request now says.
     *
     * <p>A request not waiting for its decision is left alone, flag and all. A decided request's flag records how
     * it stood when it was decided.</p>
     *
     * @param resource the time off request to judge; a resource that is not a {@code sub:Submission} is left alone
     * @param today the day to count as today, in the server's zone
     * @throws PersistenceException when the tag cannot be written
     */
    static void mark(final Resource resource, final LocalDate today) throws PersistenceException
    {
        final Submission submission = resource.adaptTo(Submission.class);
        if (submission == null) {
            // Not a submission, so there is nothing to flag
            return;
        }
        final Taggable taggable = Objects.requireNonNull(resource.adaptTo(Taggable.class),
            "Any resource can be read as taggable content");
        if (!taggable.hasOwnTag(SUBMITTED_TAG)) {
            return;
        }
        if (startsSoon(submission, today)) {
            taggable.tag(URGENT_TAG, true);
        } else {
            taggable.untag(URGENT_TAG, true);
        }
    }

    /**
     * Whether the time off this request asks for begins tomorrow, today, or has already begun.
     *
     * @param submission a submission answering the time off request schema
     * @param today the day to count as today, in the server's zone
     * @return {@code true} if it starts no later than the day after {@code today}; {@code false} if it starts later,
     *     or its start date is unanswered or not a date
     */
    static boolean startsSoon(final Submission submission, final LocalDate today)
    {
        final LocalDate start = startDate(submission);
        // No later than tomorrow, not just today or tomorrow: undecided time off that has already begun is the
        // most pressing case of all
        return start != null && !start.isAfter(today.plusDays(1));
    }

    /**
     * The day the time off starts, as the request answers it.
     *
     * @param submission a submission answering the time off request schema
     * @return the date, or {@code null} when it has not been answered or is not a date
     */
    private static LocalDate startDate(final Submission submission)
    {
        final String answer = submission.getAnswerTo(TimeOffQuestions.START_DATE_QUESTION);
        final LocalDate start = DateUtils.parseDate(answer);
        if (answer != null && start == null) {
            // Not thrown, so that one unreadable answer does not stop the others being judged, but recorded, since
            // the request then never reads as urgent
            LOGGER.warn("Could not read {} as the day time off starts", answer);
            ErrorLogger.logProblem("A time off start date cannot be read",
                ErrorContext.of(TimeOffUrgency.class, "startDate").about(submission.getPath()).with("answer", answer));
        }
        return start;
    }
}
