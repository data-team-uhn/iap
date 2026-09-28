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
package io.uhndata.iap.schemas.summary.internal;

import java.util.Arrays;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.SchemasHomepage;
import io.uhndata.iap.utils.summary.AdminSummaryServlet;
import io.uhndata.iap.utils.summary.SummaryUnavailableException;

/**
 * Counts the schemas for the console widget: {@code GET /Schemas.adminSummary.json} answers with how many versions
 * are active, how many drafts are in progress, and how many schemas are retired.
 *
 * <p>
 * Each figure counts the schemas or versions carrying that lifecycle tag themselves. The children are read
 * directly, so the counts are exact.
 * </p>
 *
 * {@snippet lang=json :
 * {
 *   "active": {"label": "Active versions", "value": 4},
 *   "drafts": {"label": "Draft versions in progress", "value": 1},
 *   "retired": {"label": "Retired schemas", "value": 2}
 * }
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = SchemasHomepage.RESOURCE_TYPE,
    selectors = AdminSummaryServlet.SELECTOR,
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class SchemasSummaryServlet extends AdminSummaryServlet
{
    private static final long serialVersionUID = 1L;

    /** The property holding the names of the tags placed on a node. */
    private static final String TAGS_PROPERTY = "tags";

    @Override
    protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException
    {
        final SchemasHomepage homepage = request.getResource().adaptTo(SchemasHomepage.class);
        if (homepage == null) {
            throw new SummaryUnavailableException("The schemas cannot be read");
        }
        final List<Schema> schemas = homepage.getSchemas();
        final List<SchemaVersion> versions = schemas.stream().flatMap(schema -> schema.getVersions().stream())
            .toList();
        return Json.createObjectBuilder()
            .add("active", count("Active versions", tagged(versions, "active")))
            .add("drafts", count("Draft versions in progress", tagged(versions, "draft")))
            .add("retired", count("Retired schemas", tagged(schemas, "retired")));
    }

    /**
     * How many of some schemas or versions carry a tag themselves, whether their {@code tags} property holds one
     * value or several.
     *
     * @param nodes the schemas or versions to count among
     * @param tag the tag name
     * @return how many carry it
     */
    private static long tagged(final List<? extends Content> nodes, final String tag)
    {
        return nodes.stream()
            .map(node -> node.get(TAGS_PROPERTY, String[].class))
            .filter(tags -> tags != null && Arrays.asList(tags).contains(tag))
            .count();
    }
}
