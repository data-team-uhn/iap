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
package io.uhndata.iap.conditions.internal;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.spi.Candidate;
import io.uhndata.iap.conditions.spi.CandidateProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionCandidatesServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionCandidatesServletTest
{
    private final SlingContext context = new SlingContext();

    private final ConditionCandidatesServlet servlet = new ConditionCandidatesServlet();

    private final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.create().resource("/Schemas/loan/v1/why");
        final CandidateProvider questions = new CandidateProvider()
        {
            @Override
            public String getSource()
            {
                return "answer";
            }

            @Override
            public List<Candidate> getCandidates(final Resource condition)
            {
                // Says where it was asked from, so the test can check the context reaches it
                return List.of(new Candidate("what", condition.getPath(), null, null, List.of()));
            }
        };
        final ConditionTypesImpl types = new ConditionTypesImpl();
        inject(ConditionTypesImpl.class, types, "providers", List.of(questions));
        inject(ConditionCandidatesServlet.class, this.servlet, "types", types);
    }

    @Test
    void listsTheCandidatesForTheContentAskedAbout() throws Exception
    {
        this.servlet.doGet(request("/Schemas/loan/v1/why", "answer"), this.response);
        assertEquals(200, this.response.getStatus());
        assertEquals("application/json", this.response.getContentType().split(";")[0]);
        final JsonArray candidates = body();
        assertEquals(1, candidates.size());
        assertEquals("what", candidates.getJsonObject(0).getString("value"));
        assertEquals("/Schemas/loan/v1/why", candidates.getJsonObject(0).getString("label"));
    }

    @Test
    void listsNothingForASourceWithoutCandidates() throws Exception
    {
        this.servlet.doGet(request("/Schemas/loan/v1/why", "property"), this.response);
        assertTrue(body().isEmpty());
    }

    @Test
    void refusesARequestThatNamesNoSource() throws Exception
    {
        this.servlet.doGet(request("/Schemas/loan/v1/why", null), this.response);
        assertEquals(400, this.response.getStatus());
    }

    private MockSlingJakartaHttpServletRequest request(final String path, final String source)
    {
        final MockSlingJakartaHttpServletRequest request = new MockSlingJakartaHttpServletRequest(
            this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(path));
        if (source != null) {
            request.setParameterMap(Map.of("source", source));
        }
        return request;
    }

    private JsonArray body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readArray();
        }
    }

    private static <T> void inject(final Class<T> type, final T target, final String field, final Object value)
        throws ReflectiveOperationException
    {
        final Field declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
