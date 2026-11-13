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
package io.uhndata.iap.submissions.models;

import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.ValidationRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Validation}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValidationTest
{
    private static final String RESOURCE_TYPE = "sling:resourceType";

    private static final String VALIDATION_PATH = "/Submissions/submission/is_proposal";

    private static final String REQUIREMENT_PATH = "/Schemas/schema/1.0/is_proposal";

    private static final String REQUIREMENT_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String OTHER_PATH = "/Schemas/schema/1.0/is_consent";

    private final SlingContext context = new SlingContext();

    private int runCount;

    @BeforeEach
    void setUp()
        throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Validation.class, Extraction.class,
            ValidationRequirement.class);
        this.context.create().resource(REQUIREMENT_PATH, Map.of(RESOURCE_TYPE, ValidationRequirement.RESOURCE_TYPE,
            "confidenceThreshold", 0.8));
        this.context.create().resource(OTHER_PATH, RESOURCE_TYPE, ValidationRequirement.RESOURCE_TYPE);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(REQUIREMENT_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(REQUIREMENT_ID)).thenReturn(node);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private Validation createValidation(final String requirement)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(RESOURCE_TYPE, Validation.RESOURCE_TYPE));
        if (requirement != null) {
            properties.put("requirement", requirement);
        }
        return this.context.create().resource(VALIDATION_PATH, properties).adaptTo(Validation.class);
    }

    private void addRun(final Map<String, Object> properties)
    {
        final Map<String, Object> run = new HashMap<>(properties);
        run.put(RESOURCE_TYPE, Extraction.RESOURCE_TYPE);
        this.context.create().resource(VALIDATION_PATH + "/run" + this.runCount++, run);
    }

    private ValidationRequirement getRequirement(final String path)
    {
        return this.context.resourceResolver().getResource(path).adaptTo(ValidationRequirement.class);
    }

    @Test
    void namesTheRequirementItDecides()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);

        assertEquals(REQUIREMENT_PATH, validation.getRequirement().getPath());
        assertTrue(validation.isAbout(this.getRequirement(REQUIREMENT_PATH)));
        assertFalse(validation.isAbout(this.getRequirement(OTHER_PATH)));
    }

    @Test
    void isAboutNothingWhenItsRequirementIsGone()
    {
        final Validation validation = this.createValidation(null);

        assertNull(validation.getRequirement());
        assertFalse(validation.isAbout(this.getRequirement(REQUIREMENT_PATH)));
    }

    @Test
    void hasNoOutcomeBeforeTheModelIsAsked()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);

        assertTrue(validation.getExtractions().isEmpty());
        assertNull(validation.getLatestExtraction());
        assertNull(validation.getOutcome());
    }

    @Test
    void readsASureYesOrNo()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "yes" }, "confidence", 0.9));
        assertEquals(Validation.OUTCOME_YES, validation.getOutcome());

        this.addRun(Map.of("extractedValues", new String[] { "no" }, "confidence", 0.8));
        assertEquals(Validation.OUTCOME_NO, validation.getOutcome());
    }

    // Only the newest run counts, even when an older one was surer
    @Test
    void readsTheNewestRun()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "yes" }, "confidence", 0.99));
        this.addRun(Map.of("extractedValues", new String[] { "no" }, "confidence", 0.85));

        assertNotNull(validation.getLatestExtraction());
        assertEquals(2, validation.getExtractions().size());
        assertEquals(Validation.OUTCOME_NO, validation.getOutcome());
    }

    @Test
    void isUnsureBelowTheRequirementsThreshold()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "yes" }, "confidence", 0.75));

        assertEquals(Validation.OUTCOME_UNSURE, validation.getOutcome());
    }

    @Test
    void usesTheDefaultThresholdWhenTheRequirementIsGone()
    {
        final Validation validation = this.createValidation(null);
        this.addRun(Map.of("extractedValues", new String[] { "yes" }, "confidence", 0.75));

        assertEquals(Validation.OUTCOME_YES, validation.getOutcome());
    }

    @Test
    void isUnsureWhenTheRunFoundNothing()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("confidence", 0.9));

        assertEquals(Validation.OUTCOME_UNSURE, validation.getOutcome());
    }

    @Test
    void isUnsureWhenTheModelGaveNoConfidence()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "yes" }));

        assertEquals(Validation.OUTCOME_UNSURE, validation.getOutcome());
    }

    @Test
    void isUnsureWhenTheModelAnsweredBothWays()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "yes", "no" }, "confidence", 0.9));

        assertEquals(Validation.OUTCOME_UNSURE, validation.getOutcome());
    }

    // Not every provider keeps the reply to the options, and a reply off the list is still a guess
    @Test
    void isUnsureWhenTheReplyIsNeitherYesNorNo()
    {
        final Validation validation = this.createValidation(REQUIREMENT_ID);
        this.addRun(Map.of("extractedValues", new String[] { "probably" }, "confidence", 0.9));

        assertEquals(Validation.OUTCOME_UNSURE, validation.getOutcome());
    }
}
