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
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the archive entries: {@code GET /Archive.entries.json} answers with one page of the deletions recorded
 * under the addressed archive subtree, newest first.
 *
 * <p>
 * This is the listing the restore and purge endpoints have always needed: both act on an entry's path, and entries
 * live in a prefix tree of buckets, so without a listing there is no way for a client to name one. Only users who
 * can see the archive — in practice, administrators — can reach this endpoint; everyone else gets a 404 from the
 * resource resolution itself, the same rule those two endpoints rely on. The query runs with the requester's own
 * session, so nothing here can widen what they may see.
 * </p>
 *
 * <p>
 * Parameters: {@code offset} and {@code limit} page the results; {@code filter} keeps only the entries whose
 * requested path or requesting user contains it, ignoring case; {@code sortBy} is one of {@code jcr:created},
 * {@code deletedBy} or {@code requestedPath}, and {@code descending} reverses the order. The effective sort is
 * echoed back, since an unrecognised column falls back to the default rather than failing the request.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { ArchiveEntriesServlet.ARCHIVE_RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "entries" }, extensions = { "json" })
public class ArchiveEntriesServlet extends SlingJakartaSafeMethodsServlet
{
    /** The {@code sling:resourceType} of the archive root and of the buckets under it. */
    static final String ARCHIVE_RESOURCE_TYPE = "del/Archive";

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveEntriesServlet.class);

    /** How many entries are returned when no {@code limit} is asked for. */
    private static final long DEFAULT_LIMIT = 25;

    /** The outcome word and wording used whichever way the query fails to run. */
    private static final String FAILED = "failed";

    private static final String UNQUERYABLE = "The archive cannot be queried";

    /** How many entries are returned at most, whatever {@code limit} asks for. */
    private static final long MAX_LIMIT = 200;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final ResourceResolver resolver = request.getResourceResolver();
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FAILED, UNQUERYABLE);
            return;
        }
        final long offset = Math.max(0, parseLong(request.getParameter("offset"), 0));
        final long limit = Math.min(Math.max(0, parseLong(request.getParameter("limit"), DEFAULT_LIMIT)), MAX_LIMIT);
        final String sortBy = Objects.requireNonNullElse(request.getParameter("sortBy"), ArchiveQuery.DEFAULT_SORT);
        final boolean descending = !"false".equals(request.getParameter("descending"));
        final String statement =
            ArchiveQuery.entries(request.getResource().getPath(), request.getParameter("filter"), sortBy, descending);
        try {
            final ArchiveSearch.Page page = ArchiveSearch.page(session, statement, offset, limit);
            send(response, resolver, page, offset, limit, sortBy, descending);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to list the archive entries: {}", e.getMessage(), e);
            JsonResponses.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FAILED, UNQUERYABLE);
        }
    }

    private void send(final SlingJakartaHttpServletResponse response, final ResourceResolver resolver,
        final ArchiveSearch.Page page, final long offset, final long limit, final String sortBy,
        final boolean descending) throws IOException
    {
        final JsonArrayBuilder rows = Json.createArrayBuilder();
        long returned = 0;
        for (final String path : page.paths()) {
            final Resource entry = resolver.getResource(path);
            // A query result can name an entry that a concurrent restore or purge has since removed; it is simply
            // no longer there to list.
            if (entry != null) {
                rows.add(ArchiveEntryRow.of(entry));
                returned++;
            }
        }
        final JsonObjectBuilder body = Json.createObjectBuilder()
            .add("rows", rows)
            .add("offset", offset)
            .add("limit", limit)
            .add("returnedrows", returned)
            .add("totalrows", page.total().value())
            .add("totalIsApproximate", page.total().approximate())
            .add("sortBy", ArchiveQuery.SORTABLE.contains(sortBy) ? sortBy : ArchiveQuery.DEFAULT_SORT)
            .add("descending", descending);
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }

    private static long parseLong(final String value, final long fallback)
    {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}
