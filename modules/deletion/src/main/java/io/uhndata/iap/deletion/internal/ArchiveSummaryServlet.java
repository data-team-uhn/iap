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

import java.time.Duration;
import java.util.function.LongSupplier;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.summary.AdminSummaryServlet;
import io.uhndata.iap.utils.summary.SummaryUnavailableException;

/**
 * Counts the archive entries: {@code GET /Archive.adminSummary.json} answers with how many deletions were recorded
 * in the last day, in the last week, and altogether.
 *
 * <p>
 * This is what the archive console widget shows, which is why it is a separate endpoint from the listing rather
 * than a field on it: the widget wants three numbers and no rows, and asking for a page of entries to read a count
 * off it would fetch what nothing displays.
 * </p>
 *
 * {@snippet lang=json :
 * {
 *   "last24Hours": {"label": "Archived in the last 24 hours", "value": 3},
 *   "lastWeek": {"label": "Archived in the last 7 days", "value": 11},
 *   "total": {"label": "Archived in total", "value": 10000, "approximate": true}
 * }
 * }
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
    selectors = { AdminSummaryServlet.SELECTOR }, extensions = { "json" })
public class ArchiveSummaryServlet extends AdminSummaryServlet
{
    private static final long serialVersionUID = 1L;

    /** The wording used whichever way the query fails to run. */
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
    protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new SummaryUnavailableException(UNQUERYABLE);
        }
        final String root = request.getResource().getPath();
        final long now = this.clock.getAsLong();
        try {
            final ArchiveSearch.Count day = this.countSince(session, root, now, Duration.ofDays(1));
            final ArchiveSearch.Count week = this.countSince(session, root, now, Duration.ofDays(7));
            final ArchiveSearch.Count total = ArchiveSearch.count(session, ArchiveQuery.all(root), this.cap);
            return Json.createObjectBuilder()
                .add("last24Hours", count("Archived in the last 24 hours", day.value(), day.approximate()))
                .add("lastWeek", count("Archived in the last 7 days", week.value(), week.approximate()))
                .add("total", count("Archived in total", total.value(), total.approximate()));
        } catch (final RepositoryException e) {
            throw new SummaryUnavailableException(UNQUERYABLE, e);
        }
    }

    private ArchiveSearch.Count countSince(final Session session, final String root, final long now,
        final Duration window) throws RepositoryException
    {
        return ArchiveSearch.count(session,
            ArchiveQuery.createdSince(root, ArchiveQuery.timestamp(now - window.toMillis())), this.cap);
    }
}
