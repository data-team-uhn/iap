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
package io.uhndata.iap.search.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.PaginatedJsonResponse;
import io.uhndata.iap.utils.summary.AdminSummaryServlet;
import io.uhndata.iap.utils.summary.SummaryUnavailableException;

/**
 * Counts what an entity homepage holds, for an administration dashboard widget.
 * {@code GET /Workflows.adminSummary.json} answers with one figure per homepage of the same kind that the caller can
 * read, the queried one first.
 *
 * <p>
 * The answer is keyed by homepage path:
 * </p>
 *
 * {@snippet lang=json :
 * {
 *   "/Workflows": {"label": "Workflows", "value": 12, "path": "/Workflows"},
 *   "/SystemWorkflows": {"label": "System workflows", "value": 8, "approximate": true, "path": "/SystemWorkflows"}
 * }
 * }
 *
 * <p>
 * A tool whose summary is more than a count of its entities registers its own servlet for the same selector on its
 * own resource type. Sling prefers that servlet over this one.
 * </p>
 *
 * <p>
 * Every query runs on the caller's own session. Homepages and entities they cannot read are left out.
 * </p>
 *
 * <p>
 * A homepage that cannot be counted keeps its figure, with a {@code null} value. The summary fails only when none
 * can be.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "data/EntityHomepage" }, methods = { "GET" },
    selectors = { AdminSummaryServlet.SELECTOR }, extensions = { "json" })
public class EntityHomepageSummaryServlet extends AdminSummaryServlet
{
    private static final long serialVersionUID = 1L;

    /**
     * Every entity homepage, of any concrete type. The ones holding the same kind of entity as the queried one are
     * picked out afterwards.
     */
    private static final String HOMEPAGE_QUERY = "select * from [data:EntityHomepage]";

    private static final String UNCOUNTABLE = "Failed to count the entities";

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityHomepageSummaryServlet.class);

    @Override
    protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException
    {
        final Resource homepage = request.getResource();
        final ResourceResolver resolver = request.getResourceResolver();
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new SummaryUnavailableException(UNCOUNTABLE);
        }
        final String childType;
        final List<Resource> peers;
        try {
            childType = QueryBuilder.childNodeType(homepage);
            peers = peers(resolver, homepage.getPath(), childType);
        } catch (final RuntimeException e) {
            // A homepage naming an invalid child type, or Oak hitting a read limit while the homepages are walked
            throw new SummaryUnavailableException(UNCOUNTABLE, e);
        }
        final JsonObjectBuilder summary = Json.createObjectBuilder();
        boolean anyCounted = false;
        for (final Resource peer : peers) {
            JsonObjectBuilder figure;
            try {
                figure = describe(peer, childType, session);
                anyCounted = true;
            } catch (final RepositoryException | RuntimeException e) {
                // One homepage failing to count loses only its own figure. Oak reports a read or memory limit
                // with an unchecked exception while the rows are walked.
                LOGGER.warn("Failed to count the entities in {}: {}", peer.getPath(), e.getMessage(), e);
                figure = unknown(title(peer));
            }
            summary.add(peer.getPath(), figure.add("path", peer.getPath()));
        }
        if (!anyCounted) {
            throw new SummaryUnavailableException(UNCOUNTABLE);
        }
        return summary;
    }

    /**
     * The homepages this summary covers: the queried one and every other holding the same kind of entity.
     *
     * @param resolver the caller's own resolver, so the list stops at what they may read
     * @param queried the path of the homepage the request was addressed to
     * @param childType the node type the queried homepage holds
     * @return the homepages to summarize, the queried one first and the rest ordered by path
     */
    private static List<Resource> peers(final ResourceResolver resolver, final String queried, final String childType)
    {
        final List<Resource> homepages = new ArrayList<>();
        final Iterator<Resource> found = resolver.findResources(HOMEPAGE_QUERY, Query.JCR_SQL2);
        while (found.hasNext()) {
            final Resource candidate = found.next();
            if (childType.equals(QueryBuilder.childNodeType(candidate))) {
                homepages.add(candidate);
            }
        }
        // The queried homepage first, then the rest by path
        homepages.sort(Comparator.comparing((final Resource homepage) -> !queried.equals(homepage.getPath()))
            .thenComparing(Resource::getPath));
        return homepages;
    }

    /**
     * One homepage as a figure: what to call it, and how many entities it holds.
     *
     * @param homepage the homepage to count
     * @param childType the node type to count under it
     * @param session the caller's own session, so entities they cannot read go uncounted
     * @return a JSON object builder holding the figure
     * @throws RepositoryException if the count query cannot be run
     */
    private static JsonObjectBuilder describe(final Resource homepage, final String childType,
        final Session session) throws RepositoryException
    {
        final RowIterator rows = new QueryBuilder(childType, homepage.getPath()).buildCount().createQuery(session)
            .execute().getRows();
        long counted = 0;
        while (counted < PaginatedJsonResponse.MAX_COUNT && rows.hasNext()) {
            rows.nextRow();
            ++counted;
        }
        // Rows left over mean counting stopped at the ceiling, and the count is a lower bound
        return count(title(homepage), counted, rows.hasNext());
    }

    private static String title(final Resource homepage)
    {
        return homepage.getValueMap().get("title", homepage.getName());
    }
}
