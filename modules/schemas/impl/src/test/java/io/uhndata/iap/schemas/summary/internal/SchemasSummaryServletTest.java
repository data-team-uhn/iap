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

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.SchemasHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link SchemasSummaryServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemasSummaryServletTest
{
    private static final String HOME = "/Schemas";

    private final SlingContext context = new SlingContext();

    private SchemasSummaryServlet servlet;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityHomepage.class, SchemasHomepage.class,
            Schema.class, SchemaVersion.class);
        this.context.create().resource(HOME, "sling:resourceType", SchemasHomepage.RESOURCE_TYPE);
        this.servlet = new SchemasSummaryServlet();
    }

    @Test
    void countsNothingWhenNoSchemaIsDefined() throws IOException
    {
        final JsonObject summary = this.summary();

        assertEquals(0, value(summary, "active"));
        assertEquals(0, value(summary, "drafts"));
        assertEquals(0, value(summary, "retired"));
    }

    @Test
    void countsVersionsByTheirStateAndSchemasByTheirRetirement() throws IOException
    {
        final String study = this.schema("study", "retired");
        this.version(study, "v1", "retired");
        this.version(study, "v2", "active");
        final String consent = this.schema("consent");
        this.version(consent, "v1", "active");
        this.version(consent, "v2", "draft");
        this.schema("empty", "retired");

        final JsonObject summary = this.summary();

        assertEquals(2, value(summary, "active"));
        assertEquals(1, value(summary, "drafts"));
        assertEquals(2, value(summary, "retired"));
    }

    @Test
    void readsATagHeldAsASingleValue() throws IOException
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            "sling:resourceType", Schema.RESOURCE_TYPE, "title", "Single", "tags", "retired"));
        this.context.create().resource(HOME + "/single", properties);
        this.version(HOME + "/single", "v1");

        // An untagged version is in no state the widget counts
        final JsonObject summary = this.summary();

        assertEquals(1, value(summary, "retired"));
        assertEquals(0, value(summary, "active"));
        assertEquals(0, value(summary, "drafts"));
    }

    @Test
    void namesEachFigureAndColoursNone() throws IOException
    {
        final JsonObject summary = this.summary();

        assertEquals("Active versions", summary.getJsonObject("active").getString("label"));
        assertEquals("Draft versions in progress", summary.getJsonObject("drafts").getString("label"));
        assertEquals("Retired schemas", summary.getJsonObject("retired").getString("label"));
        summary.values().forEach(figure -> assertFalse(figure.asJsonObject().containsKey("important")));
    }

    @Test
    void reportsAHomepageItCannotReadRatherThanAnsweringWithZeros() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        // A homepage that will not adapt, as happens when the session cannot read its properties
        request.setResource(new ResourceWrapper(this.context.resourceResolver().getResource(HOME))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return null;
            }
        });
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request, response);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertEquals("The schemas cannot be read", read(response).getString("error"));
    }

    /** How many one figure counted. */
    private static int value(final JsonObject summary, final String figure)
    {
        return summary.getJsonObject(figure).getInt("value");
    }

    /** The answer the servlet gives for the schemas. */
    private JsonObject summary() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doGet(request, response);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        return read(response);
    }

    private static JsonObject read(final MockSlingJakartaHttpServletResponse response)
    {
        try (var reader = Json.createReader(new StringReader(response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    /** Defines a schema with the given tags, returning its path. */
    private String schema(final String name, final String... tags)
    {
        return this.context.create().resource(HOME + "/" + name,
            "sling:resourceType", Schema.RESOURCE_TYPE, "title", name, "tags", tags).getPath();
    }

    /** Defines a version of a schema with the given tags. */
    private Resource version(final String schema, final String name, final String... tags)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE, "version", name));
        if (tags.length > 0) {
            properties.put("tags", tags);
        }
        return this.context.create().resource(schema + "/" + name, properties);
    }
}
