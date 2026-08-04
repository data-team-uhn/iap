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

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.errortracking.models.Acknowledgement;
import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.errortracking.models.LoggedFailure;
import io.uhndata.iap.errortracking.models.LoggedProblem;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AcknowledgeServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AcknowledgeServletTest
{
    private final SlingContext context = new SlingContext();

    private AcknowledgeServlet servlet;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, EntityHomepage.class, LoggedError.class,
            LoggedFailure.class, LoggedProblem.class, LoggedErrorsHomepage.class, Acknowledgement.class);
        this.context.create().resource("/LoggedErrors",
            "sling:resourceType", LoggedErrorsHomepage.RESOURCE_TYPE);
        this.servlet = new AcknowledgeServlet();
        TestResolvers.set(this.servlet, "tagManager", tagManager("known-issue", "wont-fix", "acknowledged"));
    }

    @Test
    void recordsWhatWasDecidedAndHowMuchHadHappenedByThen() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response =
            post(error("abc", 7), Map.of("resolution", "known-issue", "note", "fix on the way"));

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        final Resource decision = this.context.resourceResolver().getResource("/LoggedErrors/abc/decision1");
        assertNotNull(decision);
        assertEquals("known-issue", decision.getValueMap().get("resolution", String.class));
        assertEquals("fix on the way", decision.getValueMap().get("note", String.class));
        // What the count has reached now is what a later occurrence is measured against
        assertEquals(7L, decision.getValueMap().get("acknowledgedOccurrences", 0L));
        assertTrue(response.getOutputAsString().contains("/LoggedErrors/abc/decision1"));
    }

    @Test
    void decidingAgainAppendsRatherThanReplacing() throws IOException
    {
        final Resource error = error("abc", 7);

        post(error, Map.of("resolution", "known-issue"));
        post(error, Map.of("resolution", "wont-fix"));

        // Nothing here is ever destroyed, the decisions included
        assertNotNull(this.context.resourceResolver().getResource("/LoggedErrors/abc/decision1"));
        assertNotNull(this.context.resourceResolver().getResource("/LoggedErrors/abc/decision2"));
    }

    @Test
    void leavesTheNoteOutWhenNoneWasGiven() throws IOException
    {
        post(error("abc", 1), Map.of("resolution", "known-issue"));

        assertNull(this.context.resourceResolver().getResource("/LoggedErrors/abc/decision1")
            .getValueMap().get("note", String.class));
    }

    @Test
    void refusesAResolutionThatIsNotOneOfTheTriageTags() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response =
            post(error("abc", 1), Map.of("resolution", "made-up"));

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
        assertTrue(response.getOutputAsString().contains("error-triage"));
    }

    @Test
    void refusesToSayThatSomethingStillNeedsAttention() throws IOException
    {
        // The marker for "nobody has dealt with this" is computed from the decisions, never itself decided
        final MockSlingJakartaHttpServletResponse response =
            post(error("abc", 1), Map.of("resolution", LoggedError.UNACKNOWLEDGED));

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
    }

    @Test
    void refusesARequestThatDecidesNothing() throws IOException
    {
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, post(error("abc", 1), Map.of()).getStatus());
    }

    @Test
    void answersNotFoundForSomethingThatIsNotARecordedError() throws IOException
    {
        final Resource home = this.context.resourceResolver().getResource("/LoggedErrors");

        assertEquals(HttpServletResponse.SC_NOT_FOUND, post(home, Map.of("resolution", "known-issue")).getStatus());
    }

    @Test
    void reportsBeingUnableToRecordTheDecision() throws IOException
    {
        final Resource refusing = new ResourceWrapper(error("abc", 1))
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return refusingResolver();
            }
        };

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            post(refusing, Map.of("resolution", "known-issue")).getStatus());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Posts a decision to the servlet.
     *
     * @param target the resource being posted to
     * @param parameters the request parameters
     * @return the response
     * @throws IOException if the response cannot be written
     */
    private MockSlingJakartaHttpServletResponse post(final Resource target, final Map<String, Object> parameters)
        throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(target);
        request.setParameterMap(parameters);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doPost(request, response);
        return response;
    }

    /**
     * Records an error to decide about, or hands back the one already recorded under that name.
     *
     * @param name the fingerprint naming it
     * @param occurrences how often it has happened
     * @return the resource recording it
     */
    private Resource error(final String name, final long occurrences)
    {
        final Resource existing = this.context.resourceResolver().getResource("/LoggedErrors/" + name);
        if (existing != null) {
            return existing;
        }
        return this.context.create().resource("/LoggedErrors/" + name, Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "type", "java.lang.IllegalStateException",
            "stackTrace", "java.lang.IllegalStateException: boom",
            "occurrences", occurrences,
            "lastOccurrence", Calendar.getInstance()));
    }

    /**
     * A resolver that refuses to create anything, standing in for a session whose privileges are wrong.
     *
     * @return the resolver
     */
    private static ResourceResolver refusingResolver()
    {
        final ResourceResolver refusing = Mockito.mock(ResourceResolver.class);
        try {
            Mockito.when(refusing.create(Mockito.any(), Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new PersistenceException("refused"));
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
        return refusing;
    }

    /**
     * A tag manager knowing exactly the given triage tags.
     *
     * @param names the tags defined in the triage category
     * @return the manager
     */
    private static TagManager tagManager(final String... names)
    {
        final TagManager manager = Mockito.mock(TagManager.class);
        final List<TagDefinition> definitions = List.of(names).stream().map(name -> {
            final TagDefinition definition = Mockito.mock(TagDefinition.class);
            Mockito.when(definition.getName()).thenReturn(name);
            return definition;
        }).toList();
        Mockito.when(manager.findDefinitions(LoggedError.TRIAGE_CATEGORY, null)).thenReturn(definitions);
        return manager;
    }
}
