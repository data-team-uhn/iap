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
package io.uhndata.iap.errortracking.internal;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.errortracking.models.Acknowledgement;
import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.errortracking.models.LoggedFailure;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;
import io.uhndata.iap.utils.DateUtils;

/**
 * Reports the errors recorded under {@value ErrorLoggerService#LOGGED_ERRORS_PATH} in the instance's status, where a
 * system administrator learns about failures nobody was there to see.
 *
 * <p>
 * Errors somebody has acknowledged are reported apart from the others. Nothing recorded is ever deleted, so without
 * that the first error an instance hit would keep the report red forever.
 * </p>
 *
 * <p>
 * An unacknowledged failure last seen within the configured window makes the report an {@code ERROR}: something is
 * going wrong now. One seen longer ago makes it a {@code WARNING}. So does a problem, however recent, unless the
 * configuration says problems are urgent. An {@code ERROR} is what a monitoring tool pages somebody for, and a
 * mis-authored definition is somebody's to correct, not an emergency.
 * </p>
 *
 * <p>
 * Components, operations and counts describe the instance's own code, and every reader sees them. Stack traces,
 * messages, paths and users quote whatever the failing code was working on, and only the privileged report shows
 * them.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = StatusReporter.class)
@Designate(ocd = ErrorReportConfiguration.class)
public class LoggedErrorsStatusReporter implements StatusReporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggedErrorsStatusReporter.class);

    /** How many errors of each kind, acknowledged or not, the privileged report describes. */
    private static final int QUOTED_ERRORS = 10;

    /** How many of an error's sampled subjects are quoted. */
    private static final int QUOTED_SUBJECTS = 3;

    /** How many errors the summary table lists. */
    private static final int LISTED_ERRORS = 25;

    /** The service user that reads the recorded errors, as a subservice name. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

    /** What the unprivileged report shows instead of anything that quotes content. */
    private static final String HIDDEN_PLACEHOLDER = "Only the admin user sees messages, stack traces, affected "
        + "paths and users.";

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** The recording service, asked how many recordings it dropped. */
    @Reference
    private ErrorLoggerService recorder;

    /** How long after a failure was last seen it still counts as happening now, in minutes. */
    private int recentWindow = ErrorReportConfiguration.DEFAULT_RECENT_WINDOW;

    /** Whether a recent problem can make the report an {@code ERROR}. */
    private boolean problemsAreUrgent;

    @Activate
    void activate(final ErrorReportConfiguration config)
    {
        // A window of zero or less would make no failure count as happening now, and silence every ERROR
        if (config.recentFailureWindow() <= 0) {
            LOGGER.warn("A recent-failure window of {} minutes cannot say what is happening now, using {} instead",
                config.recentFailureWindow(), ErrorReportConfiguration.DEFAULT_RECENT_WINDOW);
        } else {
            this.recentWindow = config.recentFailureWindow();
        }
        this.problemsAreUrgent = config.problemsAreUrgent();
    }

    @Override
    public String getName()
    {
        return "Logged errors";
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("problems", "errors");
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        LOGGER.debug("Gathering the recorded errors for the status report");
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            final Resource home = resolver.getResource(ErrorLoggerService.LOGGED_ERRORS_PATH);
            // A Sling Model adapts a resource of any type, so the type is checked first
            final LoggedErrorsHomepage errors =
                home == null || !home.isResourceType(LoggedErrorsHomepage.RESOURCE_TYPE) ? null
                    : home.adaptTo(LoggedErrorsHomepage.class);
            if (errors == null) {
                // Without the container, every recording is lost
                LOGGER.warn("{} is missing or of the wrong type, errors cannot be recorded",
                    ErrorLoggerService.LOGGED_ERRORS_PATH);
                return new StatusReport("*ERROR*: Errors cannot be logged", StatusReport.Status.ERROR,
                    ErrorLoggerService.LOGGED_ERRORS_PATH + " does not exist, so no error can be recorded there. "
                        + "The repository was most likely not initialized properly.");
            }
            return describe(errors, dropped(), unprivileged);
        } catch (final Exception e) {
            LOGGER.warn("Failed to report the recorded errors: {}", e.getMessage(), e);
            // The message of a repository failure often quotes a path
            return new StatusReport("*ERROR*: Could not report logged errors", StatusReport.Status.ERROR,
                unprivileged ? HIDDEN_PLACEHOLDER : e.getMessage());
        }
    }

    /**
     * How many recordings the recording service dropped.
     *
     * @return a count; zero if it dropped none, or if no recording service is set
     */
    private long dropped()
    {
        return this.recorder == null ? 0 : this.recorder.getDroppedCount();
    }

    /**
     * Describes what has been recorded.
     *
     * @param errors the container of the recorded errors
     * @param dropped how many recordings the recording service dropped
     * @param unprivileged {@code true} to leave out whatever quotes content: messages, stack traces, paths, users and
     *            the notes on decisions
     * @return the report
     */
    private StatusReport describe(final LoggedErrorsHomepage errors, final long dropped,
        final boolean unprivileged)
    {
        final List<LoggedError> unacknowledged = errors.getUnacknowledgedErrors();
        final List<LoggedError> acknowledged = errors.getAcknowledgedErrors();
        // One cutoff for the whole report, so that every error is measured against the same moment
        final long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(this.recentWindow);
        final List<LoggedError> ongoing = unacknowledged.stream().filter(error -> isOngoing(error, cutoff)).toList();
        LOGGER.debug("Found {} recorded errors, {} of them unacknowledged, {} of those still happening, {} not kept "
            + "up with", unacknowledged.size() + acknowledged.size(), unacknowledged.size(), ongoing.size(), dropped);

        if (unacknowledged.isEmpty() && acknowledged.isEmpty()) {
            // Nothing recorded, yet something dropped: faults arrived faster than they could be written
            return dropped == 0 ? new StatusReport("No errors are logged", StatusReport.Status.DEBUG, "")
                : new StatusReport("*WARNING*: " + plural(dropped, "error") + " could not be recorded",
                    StatusReport.Status.WARNING, overflow(dropped));
        }
        if (unacknowledged.isEmpty() && dropped == 0) {
            // Something did break, so not SUCCESS. INFO is shown to a person who asks, and not to a monitor that
            // watches for WARNING or worse
            return new StatusReport(
                acknowledged.size() == 1 ? "The one logged error has been acknowledged"
                    : "All " + acknowledged.size() + " logged errors have been acknowledged",
                StatusReport.Status.INFO, unprivileged ? "" : body(List.of(), acknowledged));
        }
        // An ERROR only for something going wrong now. An overflow alone makes a WARNING: the dropped count lasts
        // until the recording service restarts, and does not say that anything is happening now
        final StatusReport.Status level =
            ongoing.isEmpty() ? StatusReport.Status.WARNING : StatusReport.Status.ERROR;
        return new StatusReport(summarize(unacknowledged, ongoing, acknowledged, dropped), level,
            overflow(dropped) + (unprivileged ? summaryTable(unacknowledged) + "\n" + HIDDEN_PLACEHOLDER
                : body(unacknowledged, acknowledged)));
    }

    /**
     * Whether an error is still happening: it was last seen at or after the cutoff. A problem never is, unless the
     * configuration says problems are urgent.
     *
     * @param error the error to judge
     * @param cutoff the moment before which an occurrence no longer counts as now, in milliseconds since the epoch
     * @return {@code true} if this error is still happening
     */
    private boolean isOngoing(final LoggedError error, final long cutoff)
    {
        if (!(error instanceof LoggedFailure) && !this.problemsAreUrgent) {
            return false;
        }
        // Never null: it falls back to when the error was first seen, and then to the epoch
        return error.getLastOccurrence().getTimeInMillis() >= cutoff;
    }

    /**
     * The headline: how much needs attention, how much has been acknowledged, and how much was dropped.
     *
     * @param unacknowledged the errors nobody has acknowledged
     * @param ongoing those of them that are still happening
     * @param acknowledged the acknowledged errors
     * @param dropped how many recordings the recording service dropped
     * @return one line
     */
    private String summarize(final List<LoggedError> unacknowledged, final List<LoggedError> ongoing,
        final List<LoggedError> acknowledged, final long dropped)
    {
        final StringBuilder headline = new StringBuilder();
        if (unacknowledged.isEmpty()) {
            // Reached only when something was dropped
            headline.append("Nothing logged needs attention, and ").append(dropped)
                .append(" could not be recorded at all");
        } else {
            describeUnacknowledged(headline, unacknowledged, ongoing, dropped);
        }
        if (!acknowledged.isEmpty()) {
            headline.append(", and ").append(acknowledged.size()).append(" already acknowledged");
        }
        return headline.toString();
    }

    /**
     * The part of the headline about what needs attention.
     *
     * @param headline the headline being built
     * @param unacknowledged the errors nobody has acknowledged, not empty
     * @param ongoing those of them that are still happening
     * @param dropped how many recordings the recording service dropped
     */
    private void describeUnacknowledged(final StringBuilder headline, final List<LoggedError> unacknowledged,
        final List<LoggedError> ongoing, final long dropped)
    {
        final long occurrences = unacknowledged.stream().mapToLong(LoggedError::getOccurrences).sum();
        headline.append(unacknowledged.size() == 1 ? "There is 1 error logged"
            : "There are " + unacknowledged.size() + " errors logged");
        if (occurrences != unacknowledged.size()) {
            headline.append(", ").append(occurrences).append(" occurrences in total");
        }
        // Says why the report is at its level. For a WARNING, it says that nothing was seen recently. For an ERROR,
        // it says how many errors are still happening, unless all of them are
        if (ongoing.isEmpty()) {
            // While problems are not urgent, a problem seen a minute ago is not ongoing, so the headline speaks of
            // failures only
            headline.append(this.problemsAreUrgent ? ", none seen in the last " : ", no failure seen in the last ")
                .append(plural(this.recentWindow, "minute"));
        } else if (ongoing.size() < unacknowledged.size()) {
            headline.append(", ").append(ongoing.size()).append(" still happening");
        }
        if (dropped > 0) {
            headline.append(", and ").append(dropped).append(" that could not be recorded at all");
        }
    }

    /**
     * Says how many recordings were dropped. It names only a count, so every reader sees it.
     *
     * @param dropped how many recordings the recording service dropped
     * @return one Markdown paragraph, or an empty string if nothing was dropped
     */
    private static String overflow(final long dropped)
    {
        return dropped == 0 ? ""
            : "**" + plural(dropped, "error") + " could not be recorded**: more distinct faults were waiting to be "
                + "written than this instance keeps in hand. What they were is in the log file only.\n\n";
    }

    /**
     * A count followed by a noun, such as {@code 1 error} or {@code 3 errors}.
     *
     * @param count how many there are
     * @param noun what they are, in the singular
     * @return the count and the noun, with an {@code s} unless the count is one
     */
    private static String plural(final long count, final String noun)
    {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * The body of the privileged report. It holds the summary table, the most recently seen unacknowledged errors in
     * full, and a line for each of the most recently seen acknowledged ones.
     *
     * @param unacknowledged the errors nobody has acknowledged, most recently seen first
     * @param acknowledged the acknowledged errors, most recently seen first
     * @return Markdown
     */
    private static String body(final List<LoggedError> unacknowledged, final List<LoggedError> acknowledged)
    {
        final StringBuilder text = new StringBuilder(unacknowledged.isEmpty() ? "" : summaryTable(unacknowledged));
        unacknowledged.stream().limit(QUOTED_ERRORS).forEach(error -> text.append('\n').append(detail(error)));
        if (unacknowledged.size() > QUOTED_ERRORS) {
            text.append("\n_...and ").append(unacknowledged.size() - QUOTED_ERRORS).append(" more._\n");
        }
        if (!acknowledged.isEmpty()) {
            text.append("\n### Already acknowledged\n\n");
            acknowledged.stream().limit(QUOTED_ERRORS).forEach(error -> text.append(oneLine(error)));
            if (acknowledged.size() > QUOTED_ERRORS) {
                text.append("_...and ").append(acknowledged.size() - QUOTED_ERRORS).append(" more._\n");
            }
        }
        return text.toString();
    }

    /**
     * The errors at a glance. It names only code, counts and dates, so every reader sees it.
     *
     * @param errors the errors to list, most recently seen first
     * @return a Markdown table
     */
    private static String summaryTable(final List<LoggedError> errors)
    {
        final StringBuilder table = new StringBuilder(
            "| Occurrences | Last seen | Component | Operation | Failure |\n| --- | --- | --- | --- | --- |\n");
        errors.stream().limit(LISTED_ERRORS).forEach(error -> table
            .append("| ").append(error.getOccurrences())
            .append(" | ").append(moment(error.getLastOccurrence()))
            .append(" | ").append(code(error.getComponent()))
            .append(" | ").append(code(error.getOperation()))
            .append(" | ").append(code(error.getSummary()))
            .append(" |\n"));
        if (errors.size() > LISTED_ERRORS) {
            table.append("\n_...and ").append(errors.size() - LISTED_ERRORS).append(" more._\n");
        }
        return table.toString();
    }

    /**
     * Everything known about one error.
     *
     * @param error the error to describe
     * @return Markdown
     */
    private static String detail(final LoggedError error)
    {
        final StringBuilder text = new StringBuilder("\n### ").append(code(error.getComponent()))
            .append(" while ").append(code(error.getOperation())).append("\n\n")
            .append("**").append(error.getOccurrences()).append(" occurrence")
            .append(error.getOccurrences() == 1 ? "" : "s").append("**, first seen ")
            .append(moment(error.getFirstOccurrence())).append(", last seen ")
            .append(moment(error.getLastOccurrence())).append(".\n");
        appendSubjects(text, error);
        if (error instanceof final LoggedFailure thrown) {
            appendMessages(text, error);
            text.append("\n```\n").append(thrown.getStackTrace()).append("\n```\n");
        } else {
            text.append("\nWhat is wrong: ").append(code(error.getSummary())).append("\n");
            appendMessages(text, error);
        }
        if (error.getLastContext() != null) {
            text.append("\nContext of the last occurrence:\n\n```\n").append(error.getLastContext()).append("\n```\n");
        }
        return text.toString();
    }

    /**
     * Lists the first few sampled subjects of an error, and says that they are a sample.
     *
     * @param text the report being built
     * @param error the error being described
     */
    private static void appendSubjects(final StringBuilder text, final LoggedError error)
    {
        final List<String> subjects = error.getSubjects();
        if (subjects.isEmpty()) {
            return;
        }
        text.append("\nAffected at least ").append(subjects.size())
            .append(subjects.size() == 1 ? " subject" : " subjects").append(", most recently:\n\n");
        subjects.stream().limit(QUOTED_SUBJECTS).forEach(subject -> text.append("- ").append(code(subject))
            .append('\n'));
        if (subjects.size() > QUOTED_SUBJECTS) {
            text.append("- _...and more._\n");
        }
    }

    /**
     * Lists the distinct messages an error was seen with. For a problem, these are the reported phrases that differ
     * from the problem's name.
     *
     * @param text the report being built
     * @param error the error being described
     */
    private static void appendMessages(final StringBuilder text, final LoggedError error)
    {
        final List<String> messages = error.getMessages();
        if (!messages.isEmpty()) {
            text.append("\nMessages seen: ")
                .append(messages.stream().map(LoggedErrorsStatusReporter::code).collect(Collectors.joining(", ")))
                .append('\n');
        }
    }

    /**
     * One line about an acknowledged error, with the resolution and the note of its latest decision.
     *
     * @param error the error to describe
     * @return one Markdown list item
     */
    private static String oneLine(final LoggedError error)
    {
        final Acknowledgement decision = error.getLatestAcknowledgement();
        final StringBuilder line = new StringBuilder("- ").append(code(error.getSummary()))
            .append(" in ").append(code(error.getComponent()))
            .append(" — ").append(error.getOccurrences()).append(" occurrences, last seen ")
            .append(moment(error.getLastOccurrence()));
        if (decision != null) {
            line.append(" — _").append(decision.getResolution()).append('_');
            if (decision.getNote() != null) {
                line.append(": ").append(decision.getNote());
            }
        }
        return line.append('\n').toString();
    }

    /**
     * Renders a value as inline Markdown code, so that a class name or a path keeps its characters.
     *
     * @param value the value to render, or {@code null}
     * @return the value in backticks, or an em dash if it is {@code null} or empty
     */
    private static String code(final String value)
    {
        return value == null || value.isEmpty() ? "—" : "`" + value + "`";
    }

    /**
     * Renders a date in the platform's format, such as {@code 2026-01-31T20:30:00.000+00:00}.
     *
     * @param moment the date to render
     * @return the date in the platform's format, or an em dash if {@link DateUtils} cannot format it
     */
    private static String moment(final Calendar moment)
    {
        return Objects.requireNonNullElse(DateUtils.toString(moment), "—");
    }
}
