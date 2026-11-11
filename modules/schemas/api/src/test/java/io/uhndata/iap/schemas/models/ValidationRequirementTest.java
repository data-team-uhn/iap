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
package io.uhndata.iap.schemas.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ValidationRequirement}, and through it {@link DecisionRequirement}: the prompt and document
 * a decision names, its threshold, and its reference text.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValidationRequirementTest
{
    private static final String PATH = "/Schemas/schema/1.0/is_proposal";

    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ValidationRequirement.class);
    }

    private ValidationRequirement getRequirement()
    {
        return this.context.resourceResolver().getResource(PATH).adaptTo(ValidationRequirement.class);
    }

    @Test
    void exposesWhatTheModelIsAskedAndAboutWhichDocument()
    {
        this.context.create().resource(PATH, Map.of(
            TYPE, ValidationRequirement.RESOURCE_TYPE,
            "label", "Is this a research proposal?",
            "prompt", "Decide whether the document is a research protocol.",
            "document", "proposal",
            "confidenceThreshold", 0.8));
        final ValidationRequirement requirement = getRequirement();

        assertEquals("Decide whether the document is a research protocol.", requirement.getPrompt());
        assertEquals("proposal", requirement.getDocumentRequirementName());
        assertEquals(0.8, requirement.getConfidenceThreshold());
    }

    // A schema sets a threshold only when it wants a different one
    @Test
    void usesTheDefaultThresholdWhenNoneIsGiven()
    {
        this.context.create().resource(PATH, TYPE, ValidationRequirement.RESOURCE_TYPE);
        final ValidationRequirement requirement = getRequirement();

        assertEquals(DecisionRequirement.DEFAULT_CONFIDENCE_THRESHOLD, requirement.getConfidenceThreshold());
        assertNull(requirement.getPrompt());
        assertNull(requirement.getDocumentRequirementName());
        assertNull(requirement.getTemplate(), "a template is optional");
    }

    @Test
    void findsItsTemplate()
    {
        this.context.create().resource(PATH, TYPE, ValidationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/template", "jcr:primaryType", "nt:file");

        assertNotNull(getRequirement().getTemplate());
    }

    // A Markdown file loaded as initial content keeps its extension in its node name
    @Test
    void findsATemplateLoadedAsMarkdown()
    {
        this.context.create().resource(PATH, TYPE, ValidationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/template.md", "jcr:primaryType", "nt:file");

        assertEquals("template.md", getRequirement().getTemplate().getName());
    }

    // It reads as a Requirement, so the schema version lists it with the others
    @Test
    void readsAsARequirement()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, ValidationRequirement.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(Requirement.class) instanceof ValidationRequirement);
    }
}
