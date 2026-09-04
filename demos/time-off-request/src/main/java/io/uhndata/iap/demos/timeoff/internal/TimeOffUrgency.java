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
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.DateUtils;

/**
 * Whether a time off request is about to start, and saying so on the request.
 *
 * <p>Time off that begins today or tomorrow is worth flagging: whoever has to decide it has hours rather than days,
 * and a list of a dozen requests says nothing about which one that is. The rule is the demo's own — a real
 * deployment would have its own notice period, and might have several — so it lives here rather than in the
 * platform, alongside the budget rule it sits next to.</p>
 *
 * <p>The decision is a pure function of the request and a date, which is the whole reason it is here rather than
 * inside either caller: it is asked twice, once when a request is sent and once by a nightly sweep, and the two
 * must not be able to disagree. Time is passed in rather than read, so a test can ask about a Tuesday.</p>
 *
 * <p>Marking both tags and untags, so long as the request is still live: one whose start date moves later stops
 * being urgent, and a flag that only ever went on would leave the list it was meant to shorten no shorter. A
 * decided request is left exactly as it is — see {@link #mark}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TimeOffUrgency
{
    /** The name of the tag a request about to start carries. */
    static final String URGENT_TAG = "urgent";

    /**
     * Where the start date lives, relative to the schema version. Matched as a suffix so that a second version of
     * the schema needs no change here: what matters is which question it is, not which version it belongs to.
     */
    static final String START_DATE = "/details/startDate";

    /**
     * The lifecycle states a request is not coming back from. Urgency is about what still needs attention, and a
     * request that has been decided needs none — flagging it would be noise in the one list the flag exists to
     * make readable.
     */
    private static final Set<String> DECIDED = Set.of("approved", "rejected", "expired");

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeOffUrgency.class);

    private TimeOffUrgency()
    {
        // Utility class
    }

    /**
     * Places or removes the urgency flag, according to what the request now says.
     *
     * <p>A request that has already been decided is left alone entirely, flag and all. Urgency is a claim about
     * what somebody still has to do, and a decided request is not waiting for anybody — so whatever it carries is
     * the record of how it stood when it was decided, and rewriting that every night would be editing a finished
     * request to say something nobody is going to read.</p>
     *
     * @param resource the submission to judge
     * @param today the date to judge it against
     * @throws PersistenceException when the tag cannot be written
     */
    static void mark(final Resource resource, final LocalDate today) throws PersistenceException
    {
        final Submission submission = resource.adaptTo(Submission.class);
        if (submission == null) {
            // Not a submission at all, so there is nothing here to flag — and asked before the tags view, which
            // throws outright where the tags service is missing rather than reporting itself absent
            return;
        }
        final Taggable taggable = Objects.requireNonNull(resource.adaptTo(Taggable.class),
            "Any resource can be read as taggable content");
        if (isDecided(taggable)) {
            return;
        }
        if (startsSoon(submission, today)) {
            taggable.tag(URGENT_TAG, true);
        } else {
            // Unconditionally, as with every other computed flag: untag answers "make sure it is not carried",
            // and reading the tag first only to decide whether to remove it says the same thing more slowly
            taggable.untag(URGENT_TAG, true);
        }
    }

    /**
     * Whether this request has been decided, and so is nobody's outstanding work any more.
     *
     * @param taggable the request, read for its lifecycle state
     * @return {@code true} if it has been approved, rejected or expired
     */
    static boolean isDecided(final Taggable taggable)
    {
        return DECIDED.stream().anyMatch(taggable::hasOwnTag);
    }

    /**
     * Whether the time off this request asks for begins today or has already begun.
     *
     * @param submission the request
     * @param today the date to judge it against
     * @return {@code true} if it starts no later than tomorrow
     */
    static boolean startsSoon(final Submission submission, final LocalDate today)
    {
        final LocalDate start = startDate(submission);
        // Not "today or tomorrow" but "no later than tomorrow": a request whose time off has already begun is not
        // less pressing for having been left, and dropping the flag then would hide the worst case
        return start != null && !start.isAfter(today.plusDays(1));
    }

    /**
     * The day the time off starts, as the request answers it.
     *
     * @param submission the request
     * @return the date, or {@code null} when it has not been answered or is not a date
     */
    private static LocalDate startDate(final Submission submission)
    {
        final String answer = submission.getAnswerTo(START_DATE);
        final LocalDate start = DateUtils.parseDate(answer);
        if (answer != null && start == null) {
            // Said rather than thrown: one unreadable answer must not stop a nightly sweep reaching the rest
            LOGGER.warn("Could not read {} as the day time off starts", answer);
        }
        return start;
    }
}
