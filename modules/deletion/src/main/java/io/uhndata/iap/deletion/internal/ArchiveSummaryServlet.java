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
package io.uhndata.iap.deletion.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts the archive entries: {@code GET /Archive.summary.json} answers with how many deletions were recorded in
 * the last day, in the last week, and altogether.
 *
 * <p>
 * This is what the archive dashboard widget shows, which is why it is a separate endpoint from the listing rather
 * than a field on it: the widget wants three numbers and no rows, and asking for a page of entries to read a count
 * off it would fetch what nothing displays.
 * </p>
 *
 * <p>
 * Like the listing, it is reachable only by users who can see the archive, and it counts with the requester's own
 * session. Counting stops at a bound, so the answer says whether it is exact.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { ArchiveEntriesServlet.ARCHIVE_RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "summary" }, extensions = { "json" })
public class ArchiveSummaryServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveSummaryServlet.class);

    /** The outcome word and wording used whichever way the query fails to run. */
    private static final String FAILED = "failed";

    private static final String UNQUERYABLE = "The archive cannot be queried";

    /** Reads the current instant. A field so that tests can pin the windows down instead of racing them. */
    private final transient LongSupplier clock;

    /** How many entries to count before giving up and saying the answer is a lower bound. */
    private final long cap;

    /**
     * Simple constructor, used by OSGi.
     */
    public ArchiveSummaryServlet()
    {
        this(System::currentTimeMillis);
    }

    /**
     * Constructor taking the clock to read, so that tests can decide what "now" is.
     *
     * @param clock supplies the current instant, in milliseconds since the epoch
     */
    ArchiveSummaryServlet(final LongSupplier clock)
    {
        this(clock, ArchiveSearch.MAX_SCAN);
    }

    /**
     * Constructor taking the scan bound as well, so that a test can reach it without first archiving enough to hit
     * the real one.
     *
     * @param clock supplies the current instant, in milliseconds since the epoch
     * @param cap how many entries to count before stopping
     */
    ArchiveSummaryServlet(final LongSupplier clock, final long cap)
    {
        this.clock = clock;
        this.cap = cap;
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FAILED, UNQUERYABLE);
            return;
        }
        final String root = request.getResource().getPath();
        final long now = this.clock.getAsLong();
        try {
            final ArchiveSearch.Count day = this.countSince(session, root, now, Duration.ofDays(1));
            final ArchiveSearch.Count week = this.countSince(session, root, now, Duration.ofDays(7));
            final ArchiveSearch.Count total = ArchiveSearch.count(session, ArchiveQuery.all(root), this.cap);
            final JsonObjectBuilder body = Json.createObjectBuilder()
                .add("last24Hours", day.value())
                .add("lastWeek", week.value())
                .add("total", total.value())
                .add("approximate", day.approximate() || week.approximate() || total.approximate());
            JsonResponses.send(response, HttpServletResponse.SC_OK, body);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to count the archive entries: {}", e.getMessage(), e);
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FAILED, UNQUERYABLE);
        }
    }

    private ArchiveSearch.Count countSince(final Session session, final String root, final long now,
        final Duration window) throws RepositoryException
    {
        return ArchiveSearch.count(session,
            ArchiveQuery.createdSince(root, ArchiveQuery.timestamp(now - window.toMillis())), this.cap);
    }
}
