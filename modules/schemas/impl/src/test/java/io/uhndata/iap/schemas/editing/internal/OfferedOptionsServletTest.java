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
package io.uhndata.iap.schemas.editing.internal;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
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
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link OfferedOptionsServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class OfferedOptionsServletTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String QUESTION = "/Schemas/study/v1/form/kind";

    private final SlingContext context = new SlingContext();

    private final OfferedOptionsServlet servlet = new OfferedOptionsServlet();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, AnswerOption.class);
    }

    private MockSlingJakartaHttpServletResponse get(final Resource resource) throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = this.context.jakartaRequest();
        request.setResource(resource);
        final MockSlingJakartaHttpServletResponse response = this.context.jakartaResponse();
        this.servlet.doGet(request, response);
        return response;
    }

    private static JsonArray read(final MockSlingJakartaHttpServletResponse response)
    {
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject().getJsonArray("options");
    }

    // The editor's conditions then compare with the values the submitter and the model pick from
    @Test
    void servesTheItemsAQuestionTakesItsAnswersFrom() throws IOException
    {
        this.context.create().resource("/Kinds/chart", Map.of("label", "Chart review", "description", "Records only"));
        final Resource question = this.context.create().resource(QUESTION,
            Map.of(TYPE, Question.RESOURCE_TYPE, "optionsFrom", "/Kinds"));

        final JsonArray options = read(get(question));

        assertEquals(1, options.size());
        assertEquals("/Kinds/chart", options.getJsonObject(0).getString("value"));
        assertEquals("Chart review", options.getJsonObject(0).getString("label"));
        assertEquals("Records only", options.getJsonObject(0).getString("description"));
    }

    @Test
    void servesTheOptionsAQuestionLists() throws IOException
    {
        final Resource question = this.context.create().resource(QUESTION, Map.of(TYPE, Question.RESOURCE_TYPE));
        this.context.create().resource(QUESTION + "/yes", Map.of(TYPE, AnswerOption.RESOURCE_TYPE, "value", "yes"));

        assertEquals("yes", read(get(question)).getJsonObject(0).getString("value"));
    }

    @Test
    void answersNotFoundForWhatCannotBeReadAsAQuestion() throws IOException
    {
        final Resource unreadable = new ResourceWrapper(this.context.create().resource("/Kinds"))
        {
            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return null;
            }
        };

        assertEquals(HttpServletResponse.SC_NOT_FOUND, get(unreadable).getStatus());
    }
}
