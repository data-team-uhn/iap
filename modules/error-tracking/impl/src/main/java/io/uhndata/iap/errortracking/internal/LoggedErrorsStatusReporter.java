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
 * Reports the errors recorded under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}, so that a system administrator
 * watching the status of an instance learns about failures nobody was there to see.
 *
 * <p>
 * Errors somebody has already dealt with are reported apart from the ones nobody has. That distinction is what keeps
 * the report worth reading: nothing recorded here is ever deleted, so without it the first error an instance ever
 * hits would leave it reporting itself as broken forever, and a report that is always red is one nobody looks at.
 * </p>
 *
 * <p>
 * How loudly it speaks is a judgement about the instance, not about any one fault. A failure nobody has dealt with
 * that is still happening is an {@code ERROR}: something is going wrong now. One last seen longer ago than the
 * configured window is a {@code WARNING} — a fault somebody should look at, not a reason to believe this instance is
 * unwell — and so, by default, is something the instance merely found wrong rather than threw, since a mis-authored
 * definition is somebody's to correct however often it is hit. The distinction is the whole point of the levels
 * here: an {@code ERROR} is what a monitoring tool pages somebody for, and being paged for a typo in a definition
 * teaches whoever is on call to stop reading these reports.
 * </p>
 *
 * <p>
 * What the report may say depends on who is reading. A component, an operation and a count describe the instance's
 * own code and are safe to show to anyone; a stack trace, a message, a path or a user quote whatever the failing code
 * was working on, which may be anything at all, and are shown only to a reader who is logged in.
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

    /**
     * How many errors are described in full. Nothing is ever discarded from the repository, but a report nobody can
     * read through is no more useful than no report at all; the summary above it lists them all.
     */
    private static final int QUOTED_ERRORS = 10;

    /** How many of an error's sampled subjects are quoted. The rest are a listing away. */
    private static final int QUOTED_SUBJECTS = 3;

    /** How many errors the summary table lists. */
    private static final int LISTED_ERRORS = 25;

    /** The subservice name mapped to the service user allowed to read the recorded errors. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

    /** What is shown instead of anything that quotes content, to a reader who is not logged in. */
    private static final String HIDDEN = "Messages, stack traces, affected paths and users are hidden "
        + "while not logged in.";

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** The recording service, asked how much it could not keep up with. */
    @Reference
    private ErrorLoggerService recorder;

    /**
     * How long a failure counts as still happening, in minutes. Initialized rather than left to
     * {@link #activate(ErrorReportConfiguration)} so that a reporter asked for a report before it was configured
     * still answers the way its documentation says it does.
     */
    private int recentWindow = ErrorReportConfiguration.DEFAULT_RECENT_WINDOW;

    /** Whether something merely found wrong can make the report an error. */
    private boolean problemsAreUrgent;

    @Activate
    void activate(final ErrorReportConfiguration config)
    {
        // A window of zero or less would leave nothing at all counting as happening now, which is not a quieter
        // report but a report that has stopped answering the question. A deployment that wants every unacknowledged
        // failure to be loud configures a very long window instead, and the attribute's description says so
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
            // The resource type is checked before adapting, never by adapting: a Sling Model registered for one
            // resource type will happily adapt a resource of an unrelated one
            final LoggedErrorsHomepage errors =
                home == null || !home.isResourceType(LoggedErrorsHomepage.RESOURCE_TYPE) ? null
                    : home.adaptTo(LoggedErrorsHomepage.class);
            if (errors == null) {
                // Errors are being dropped on the floor rather than recorded, which is worth knowing about before
                // the failure that needed recording happens
                LOGGER.warn("{} is missing or of the wrong type, errors cannot be recorded",
                    ErrorLoggerService.LOGGED_ERRORS_PATH);
                return new StatusReport("*ERROR*: Errors cannot be logged", StatusReport.Status.ERROR,
                    ErrorLoggerService.LOGGED_ERRORS_PATH + " does not exist, so no error can be recorded there. "
                        + "The repository was most likely not initialized properly.");
            }
            return describe(errors, dropped(), unprivileged);
        } catch (final Exception e) {
            LOGGER.warn("Failed to report the recorded errors: {}", e.getMessage(), e);
            // The message of a repository failure routinely quotes a path, so it goes the same way as everything
            // else that might
            return new StatusReport("*ERROR*: Could not report logged errors", StatusReport.Status.ERROR,
                unprivileged ? HIDDEN : e.getMessage());
        }
    }

    /**
     * How many recordings the service could not keep up with. Zero rather than a failure when the service is not
     * there: this report has more important things to say in that case, and it is the one that says them.
     *
     * @return a count, zero in every healthy instance
     */
    private long dropped()
    {
        return this.recorder == null ? 0 : this.recorder.getDroppedCount();
    }

    /**
     * Describes what has been recorded.
     *
     * @param errors the container holding the recorded errors
     * @param dropped how many recordings could not be kept up with
     * @param unprivileged whether the report is going somewhere anybody can read
     * @return the report
     */
    private StatusReport describe(final LoggedErrorsHomepage errors, final long dropped,
        final boolean unprivileged)
    {
        final List<LoggedError> unacknowledged = errors.getUnacknowledgedErrors();
        final List<LoggedError> acknowledged = errors.getAcknowledgedErrors();
        // One cutoff for the whole report rather than one per error, so that everything in it is measured against
        // the same moment and two errors seen a millisecond apart cannot fall on opposite sides of it
        final long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(this.recentWindow);
        final List<LoggedError> ongoing = unacknowledged.stream().filter(error -> isOngoing(error, cutoff)).toList();
        LOGGER.debug("Found {} recorded errors, {} of them unacknowledged, {} of those still happening, {} not kept "
            + "up with", unacknowledged.size() + acknowledged.size(), unacknowledged.size(), ongoing.size(), dropped);

        if (unacknowledged.isEmpty() && acknowledged.isEmpty()) {
            // Nothing recorded and yet something dropped means faults arrived faster than they could be written,
            // which is a worse thing to be silent about than any single one of them
            return dropped == 0 ? new StatusReport("No errors are logged", StatusReport.Status.DEBUG, "")
                : new StatusReport("*WARNING*: " + plural(dropped, "error") + " could not be recorded",
                    StatusReport.Status.WARNING, overflow(dropped));
        }
        if (unacknowledged.isEmpty() && dropped == 0) {
            // Something did break, so SUCCESS would be a lie, but somebody has taken responsibility for all of it.
            // INFO is visible to a person asking and invisible to a monitor watching for anything worse, which is
            // exactly the difference acknowledging an error is meant to make
            return new StatusReport(
                acknowledged.size() == 1 ? "The one logged error has been acknowledged"
                    : "All " + acknowledged.size() + " logged errors have been acknowledged",
                StatusReport.Status.INFO, unprivileged ? "" : body(List.of(), acknowledged));
        }
        // Red only for something going wrong now. Nothing recorded here is ever deleted, so a report that turned
        // red for the oldest fault in it would be red for the rest of the instance's life, and a report that is
        // always red is one nobody looks at — the same reason acknowledged errors are reported apart from the rest.
        // An overflow does not make it red either: losing recordings is worth saying loudly, and what it says is
        // that faults once arrived faster than they could be written, not that any of them is happening now
        final StatusReport.Status level =
            ongoing.isEmpty() ? StatusReport.Status.WARNING : StatusReport.Status.ERROR;
        return new StatusReport(summarize(unacknowledged, ongoing, acknowledged, dropped), level,
            overflow(dropped) + (unprivileged ? summaryTable(unacknowledged) + "\n" + HIDDEN
                : body(unacknowledged, acknowledged)));
    }

    /**
     * Whether an error is one this instance is still getting wrong, as opposed to one it got wrong a while ago and
     * has not since. What makes a report red, and so the one judgement here worth configuring.
     *
     * <p>
     * A problem — something found wrong with nothing thrown — is not ongoing unless the deployment says problems
     * are, however recently it was seen: it is a mis-authored definition, and it will go on being hit until somebody
     * corrects it, which is a thing to be told about rather than woken for.
     * </p>
     *
     * @param error the error to judge
     * @param cutoff the moment before which an occurrence no longer counts as now, in milliseconds since the epoch
     * @return {@code true} when this error is still happening
     */
    private boolean isOngoing(final LoggedError error, final long cutoff)
    {
        if (!(error instanceof LoggedFailure) && !this.problemsAreUrgent) {
            return false;
        }
        // Never null: an error with no recorded moment falls back to when it was first seen, and then to the epoch
        return error.getLastOccurrence().getTimeInMillis() >= cutoff;
    }

    /**
     * The headline: how much needs attention, how much has already been dealt with, and how much never made it into
     * the repository at all.
     *
     * @param unacknowledged the errors nobody has dealt with
     * @param ongoing those of them that are still happening
     * @param acknowledged the errors somebody has
     * @param dropped how many recordings could not be kept up with
     * @return one line
     */
    private String summarize(final List<LoggedError> unacknowledged, final List<LoggedError> ongoing,
        final List<LoggedError> acknowledged, final long dropped)
    {
        final StringBuilder headline = new StringBuilder();
        if (unacknowledged.isEmpty()) {
            // Only reachable with something dropped: nothing that was recorded needs attention, and the report is
            // still not a clean one
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
     * @param headline the headline under construction
     * @param unacknowledged the errors nobody has dealt with, never empty
     * @param ongoing those of them that are still happening
     * @param dropped how many recordings could not be kept up with
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
        // Why this report is the level it is: a reader owed a WARNING about several errors is owed the reason it is
        // not worse, and one reading an ERROR about many is owed the count that made it one. Nothing is added when
        // every one of them is still happening, which is the case the plain sentence above already describes
        if (ongoing.isEmpty()) {
            // Not "none seen recently": with problems left out of the judgement, one hit a minute ago is still not
            // ongoing, and a headline claiming nothing had been seen would be false about it
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
     * Says what could not be kept up with, so that being unable to record a fault is not itself a silent failure.
     * Names only counts, so it is safe to show to anyone.
     *
     * @param dropped how many recordings could not be kept up with
     * @return one Markdown paragraph, empty when nothing was dropped
     */
    private static String overflow(final long dropped)
    {
        return dropped == 0 ? ""
            : "**" + plural(dropped, "error") + " could not be recorded**: more distinct faults were waiting to be "
                + "written than this instance keeps in hand. What they were is in the log file only.\n\n";
    }

    /**
     * Counts something in words a reader does not trip over.
     *
     * @param count how many there are
     * @param noun what they are, in the singular
     * @return the count and the noun, pluralized when it has to be
     */
    private static String plural(final long count, final String noun)
    {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * The whole report body for a reader who may see everything: what is wrong, then the detail of the worst of it.
     *
     * @param unacknowledged the errors nobody has dealt with
     * @param acknowledged the errors somebody has
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
     * The errors at a glance. Names only code and counts, so it is the part of the report anybody may read.
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
        if (error instanceof LoggedFailure thrown) {
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
     * Lists a sample of what an error happened to, making clear that it is a sample.
     *
     * @param text the report under construction
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
     * Lists the distinct messages one error was seen with, which are several precisely because what varies between
     * occurrences does not take part in deciding what counts as the same error. For a problem those are the phrases
     * the caller reported, which is where a phrase too variable to name the fault by ends up.
     *
     * @param text the report under construction
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
     * One line about an error nobody needs to act on, saying what silenced it so that nothing is silenced invisibly.
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
     * Renders a value as inline code, so that a class name or a path survives Markdown intact.
     *
     * @param value the value to render, may be {@code null}
     * @return the value in backticks, or a dash when there is nothing to show
     */
    private static String code(final String value)
    {
        return value == null || value.isEmpty() ? "—" : "`" + value + "`";
    }

    /**
     * Renders a date the way the rest of the platform does — milliseconds included, and a zero offset written as
     * {@code +00:00} rather than {@code Z}, which is why this goes through {@link DateUtils} rather than through one
     * of the formatters the JDK offers.
     *
     * @param moment the date to render
     * @return the date in the platform's format
     */
    private static String moment(final Calendar moment)
    {
        // The fallback is for the formatter's documented refusal to render some dates at all; a cell reading "—" is
        // the same thing the table says about anything else it does not know
        return Objects.requireNonNullElse(DateUtils.toString(moment), "—");
    }
}
