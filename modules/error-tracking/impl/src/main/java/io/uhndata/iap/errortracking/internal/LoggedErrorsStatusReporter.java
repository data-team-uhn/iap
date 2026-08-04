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

import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.errortracking.models.Acknowledgement;
import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.errortracking.models.LoggedFailure;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

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
 * What the report may say depends on who is reading. A component, an operation and a count describe the instance's
 * own code and are safe to show to anyone; a stack trace, a message, a path or a user quote whatever the failing code
 * was working on, which may be anything at all, and are shown only to a reader who is logged in.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = StatusReporter.class)
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
            return describe(errors, unprivileged);
        } catch (final Exception e) {
            LOGGER.warn("Failed to report the recorded errors: {}", e.getMessage(), e);
            // The message of a repository failure routinely quotes a path, so it goes the same way as everything
            // else that might
            return new StatusReport("*ERROR*: Could not report logged errors", StatusReport.Status.ERROR,
                unprivileged ? HIDDEN : e.getMessage());
        }
    }

    /**
     * Describes what has been recorded.
     *
     * @param errors the container holding the recorded errors
     * @param unprivileged whether the report is going somewhere anybody can read
     * @return the report
     */
    private static StatusReport describe(final LoggedErrorsHomepage errors, final boolean unprivileged)
    {
        final List<LoggedError> unacknowledged = errors.getUnacknowledgedErrors();
        final List<LoggedError> acknowledged = errors.getAcknowledgedErrors();
        LOGGER.debug("Found {} recorded errors, {} of them unacknowledged",
            unacknowledged.size() + acknowledged.size(), unacknowledged.size());

        if (unacknowledged.isEmpty() && acknowledged.isEmpty()) {
            return new StatusReport("No errors are logged", StatusReport.Status.DEBUG, "");
        }
        if (unacknowledged.isEmpty()) {
            // Something did break, so SUCCESS would be a lie, but somebody has taken responsibility for all of it.
            // INFO is visible to a person asking and invisible to a monitor watching for anything worse, which is
            // exactly the difference acknowledging an error is meant to make
            return new StatusReport(
                acknowledged.size() == 1 ? "The one logged error has been acknowledged"
                    : "All " + acknowledged.size() + " logged errors have been acknowledged",
                StatusReport.Status.INFO, unprivileged ? "" : body(List.of(), acknowledged));
        }
        return new StatusReport(summarize(unacknowledged, acknowledged), StatusReport.Status.ERROR,
            unprivileged ? summaryTable(unacknowledged) + "\n" + HIDDEN : body(unacknowledged, acknowledged));
    }

    /**
     * The headline: how much needs attention, and how much has already been dealt with.
     *
     * @param unacknowledged the errors nobody has dealt with
     * @param acknowledged the errors somebody has
     * @return one line
     */
    private static String summarize(final List<LoggedError> unacknowledged, final List<LoggedError> acknowledged)
    {
        final long occurrences = unacknowledged.stream().mapToLong(LoggedError::getOccurrences).sum();
        final StringBuilder headline = new StringBuilder();
        headline.append(unacknowledged.size() == 1 ? "There is 1 error logged"
            : "There are " + unacknowledged.size() + " errors logged");
        if (occurrences != unacknowledged.size()) {
            headline.append(", ").append(occurrences).append(" occurrences in total");
        }
        if (!acknowledged.isEmpty()) {
            headline.append(", and ").append(acknowledged.size()).append(" already acknowledged");
        }
        return headline.toString();
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
            appendMessages(text, thrown);
            text.append("\n```\n").append(thrown.getStackTrace()).append("\n```\n");
        } else {
            text.append("\nWhat is wrong: ").append(code(error.getSummary())).append("\n");
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
     * Lists the distinct messages one error was seen with, which are several precisely because the message does not
     * take part in deciding what counts as the same error.
     *
     * @param text the report under construction
     * @param error the error being described
     */
    private static void appendMessages(final StringBuilder text, final LoggedFailure error)
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
     * Renders a date the way the rest of the platform does.
     *
     * @param moment the date to render
     * @return an ISO instant
     */
    private static String moment(final Calendar moment)
    {
        return DateTimeFormatter.ISO_INSTANT.format(moment.toInstant());
    }
}
