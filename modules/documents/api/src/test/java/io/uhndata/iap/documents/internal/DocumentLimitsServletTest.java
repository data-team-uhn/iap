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
package io.uhndata.iap.documents.internal;

import java.io.StringReader;
import java.lang.reflect.Field;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DocumentLimitsServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentLimitsServletTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void servesTheConfiguredLimits() throws Exception
    {
        final ConfiguredDocumentLimits limits = new ConfiguredDocumentLimits();
        limits.activate(ConfiguredDocumentLimitsTest.mockConfiguration(1000, 20, 3000));
        final DocumentLimitsServlet servlet = new DocumentLimitsServlet();
        final Field reference = DocumentLimitsServlet.class.getDeclaredField("limits");
        reference.setAccessible(true);
        reference.set(servlet, limits);

        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        servlet.doGet(
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext()),
            response);

        assertEquals(200, response.getStatus());
        assertEquals("application/json", response.getContentType().split(";")[0]);
        final JsonObject json = Json.createReader(new StringReader(response.getOutputAsString())).readObject();
        assertEquals(1000, json.getJsonNumber("maxFileSize").longValue());
        assertEquals(20, json.getInt("maxPdfPages"));
        assertEquals(3000, json.getJsonNumber("maxUnzippedSize").longValue());
    }
}
