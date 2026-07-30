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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports the errors recorded under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}, so that a system administrator
 * watching the status of an instance learns about failures nobody was there to see.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class LoggedErrorsStatusReporter implements StatusReporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggedErrorsStatusReporter.class);

    /**
     * How many distinct errors are quoted in a report. Nothing is ever discarded from the repository, but a report
     * nobody can read through is no more useful than no report at all; the counts tell the whole story, and the rest
     * is a query away.
     */
    private static final int QUOTED_ERRORS = 10;

    /** The subservice name mapped to the service user allowed to read the recorded errors. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

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
            if (home == null) {
                // Errors are being dropped on the floor rather than recorded, which is worth knowing about before
                // the failure that needed recording happens
                LOGGER.warn("{} does not exist, errors cannot be recorded", ErrorLoggerService.LOGGED_ERRORS_PATH);
                return new StatusReport("*ERROR*: Errors cannot be logged", StatusReport.Status.ERROR,
                    ErrorLoggerService.LOGGED_ERRORS_PATH + " does not exist, so no error can be recorded there. "
                        + "The repository was most likely not initialized properly.");
            }
            final List<Resource> errors = getRecordedErrors(home);
            LOGGER.debug("Found {} recorded errors", errors.size());
            if (errors.isEmpty()) {
                return new StatusReport("No errors are logged", StatusReport.Status.DEBUG, "");
            }
            if (unprivileged) {
                // A stack trace quotes whatever the failing code was working on, which may be anything at all
                return new StatusReport(summarize(errors), StatusReport.Status.ERROR,
                    "Error content is hidden while not logged in");
            }
            return new StatusReport(summarize(errors) + ":", StatusReport.Status.ERROR, quote(errors));
        } catch (final Exception e) {
            LOGGER.warn("Failed to report the recorded errors: {}", e.getMessage(), e);
            return new StatusReport("*ERROR*: Could not report logged errors", StatusReport.Status.ERROR,
                e.getMessage());
        }
    }

    /**
     * All the recorded errors, most recently seen first. They are the direct children of the homepage, so listing
     * them needs no query, and therefore no index.
     *
     * @param home the homepage holding the recorded errors
     * @return the error nodes, an empty list if none were recorded
     */
    private List<Resource> getRecordedErrors(final Resource home)
    {
        return StreamSupport.stream(home.getChildren().spliterator(), false)
            .filter(error -> error.getValueMap().containsKey(ErrorLoggerImpl.STACK_TRACE))
            .sorted(Comparator.comparing(LoggedErrorsStatusReporter::getLastOccurrence).reversed())
            .toList();
    }

    /**
     * The headline of a report: how many distinct errors were recorded, and how many times in total when the same
     * error happened more than once.
     *
     * @param errors the recorded errors
     * @return a sentence with no trailing punctuation
     */
    private String summarize(final List<Resource> errors)
    {
        final long occurrences = errors.stream().mapToLong(LoggedErrorsStatusReporter::getOccurrences).sum();
        final String headline = "There " + (errors.size() == 1 ? "is 1 error" : "are " + errors.size() + " errors")
            + " logged";
        return occurrences == errors.size() ? headline : headline + ", " + occurrences + " occurrences in total";
    }

    /**
     * Renders the recorded errors as the body of a report, quoting at most {@link #QUOTED_ERRORS} of them and saying
     * how many were left out.
     *
     * @param errors the recorded errors, most recently seen first
     * @return a Markdown body
     */
    private String quote(final List<Resource> errors)
    {
        final String quoted = errors.stream()
            .limit(QUOTED_ERRORS)
            .map(this::quoteOne)
            .collect(Collectors.joining("\n"));
        if (errors.size() <= QUOTED_ERRORS) {
            return quoted;
        }
        return quoted + "\n_...and " + (errors.size() - QUOTED_ERRORS) + " more._\n";
    }

    /**
     * Renders one recorded error: its stack trace, preceded by how often it happened when that was more than once.
     *
     * @param error a recorded error
     * @return a Markdown fragment
     */
    private String quoteOne(final Resource error)
    {
        final ValueMap values = error.getValueMap();
        final long occurrences = getOccurrences(error);
        final StringBuilder result = new StringBuilder();
        if (occurrences > 1) {
            result.append("_").append(occurrences).append(" occurrences, last seen ")
                .append(format(getLastOccurrence(error))).append("_\n\n");
        }
        return result.append("```\n").append(values.get(ErrorLoggerImpl.STACK_TRACE, "")).append("\n```\n")
            .toString();
    }

    /**
     * How many times one error was recorded. An error recorded by a session that could not count it is worth one
     * occurrence rather than none.
     *
     * @param error a recorded error
     * @return a count of at least 1
     */
    private static long getOccurrences(final Resource error)
    {
        return Math.max(1L, error.getValueMap().get(ErrorLoggerImpl.OCCURRENCES, 1L));
    }

    /**
     * When an error was last recorded, for ordering the report. Errors recorded without a date sort last rather than
     * breaking the whole report.
     *
     * @param error a recorded error
     * @return the date the error was last seen, the epoch if it is missing
     */
    private static Calendar getLastOccurrence(final Resource error)
    {
        final ValueMap values = error.getValueMap();
        final Calendar lastSeen = values.get(ErrorLoggerImpl.LAST_OCCURRENCE, Calendar.class);
        if (lastSeen != null) {
            return lastSeen;
        }
        final Calendar firstSeen = values.get("jcr:created", Calendar.class);
        if (firstSeen != null) {
            return firstSeen;
        }
        final Calendar epoch = Calendar.getInstance();
        epoch.setTimeInMillis(0);
        return epoch;
    }

    private static String format(final Calendar moment)
    {
        return DateTimeFormatter.ISO_INSTANT.format(moment.toInstant());
    }
}
