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

import java.util.List;
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
 * Unit tests for {@link ClassificationRequirement}: the prompt and document it names, its threshold, and the one
 * question whose options are the categories.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ClassificationRequirementTest
{
    private static final String PATH = "/Schemas/schema/1.0/is_proposal";

    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, AnswerOption.class,
            Section.class, ClassificationRequirement.class);
    }

    @Test
    void exposesWhatTheModelIsAskedAndAboutWhichDocument()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, ClassificationRequirement.RESOURCE_TYPE,
            "label", "Is this a research proposal?",
            "prompt", "Decide whether the document is a research protocol.",
            "document", "proposal",
            "confidenceThreshold", 0.8));
        final ClassificationRequirement requirement = resource.adaptTo(ClassificationRequirement.class);

        assertEquals("Decide whether the document is a research protocol.", requirement.getPrompt());
        assertEquals("proposal", requirement.getDocument());
        assertEquals(0.8, requirement.getConfidenceThreshold());
    }

    // A threshold is only worth writing down when a schema wants it other than the usual
    @Test
    void usesTheDefaultThresholdWhenNoneIsGiven()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        final ClassificationRequirement requirement = resource.adaptTo(ClassificationRequirement.class);

        assertEquals(ClassificationRequirement.DEFAULT_CONFIDENCE_THRESHOLD, requirement.getConfidenceThreshold());
        assertNull(requirement.getPrompt());
        assertNull(requirement.getDocument());
        assertNull(requirement.getTemplate(), "a template is optional");
    }

    @Test
    void offersTheOptionsOfItsOneQuestionAsTheCategories()
    {
        this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/decision", Map.of(TYPE, Question.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "text", "Is this a research proposal?"));
        this.context.create().resource(PATH + "/decision/yes",
            Map.of(TYPE, AnswerOption.RESOURCE_TYPE, "value", "yes", "label", "Yes"));
        this.context.create().resource(PATH + "/decision/no",
            Map.of(TYPE, AnswerOption.RESOURCE_TYPE, "value", "no", "label", "No"));
        this.context.create().resource(PATH + "/template", "jcr:primaryType", "nt:file");
        final ClassificationRequirement requirement =
            this.context.resourceResolver().getResource(PATH).adaptTo(ClassificationRequirement.class);

        assertEquals("decision", requirement.getDecision().getName());
        assertEquals(List.of("yes", "no"),
            requirement.getCategories().stream().map(OfferedOption::value).toList());
        assertNotNull(requirement.getTemplate());
    }

    // A Markdown file loaded as initial content keeps its extension in its node name
    @Test
    void findsATemplateLoadedAsMarkdown()
    {
        this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/template.md", "jcr:primaryType", "nt:file");
        final ClassificationRequirement requirement =
            this.context.resourceResolver().getResource(PATH).adaptTo(ClassificationRequirement.class);

        assertEquals("template.md", requirement.getTemplate().getName());
    }

    // A section with no question in it is not where the decision is
    @Test
    void looksPastASectionThatHoldsNoQuestion()
    {
        this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/intro", Map.of(TYPE, Section.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "title", "About this step"));
        this.context.create().resource(PATH + "/decision", Map.of(TYPE, Question.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "text", "Is this a research proposal?"));
        final ClassificationRequirement requirement =
            this.context.resourceResolver().getResource(PATH).adaptTo(ClassificationRequirement.class);

        assertEquals("decision", requirement.getDecision().getName());
    }

    // A schema may nest the decision question in a section, e.g. to give it a heading
    @Test
    void findsItsQuestionNestedInASection()
    {
        this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/group", Map.of(TYPE, Section.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "title", "Decision"));
        this.context.create().resource(PATH + "/group/decision", Map.of(TYPE, Question.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "text", "Is this a research proposal?"));
        final ClassificationRequirement requirement =
            this.context.resourceResolver().getResource(PATH).adaptTo(ClassificationRequirement.class);

        assertEquals("decision", requirement.getDecision().getName());
    }

    // A requirement written without its question has nothing to be answered in, so nothing to pick from
    @Test
    void offersNothingWithoutAQuestion()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);
        final ClassificationRequirement requirement = resource.adaptTo(ClassificationRequirement.class);

        assertNull(requirement.getDecision());
        assertTrue(requirement.getCategories().isEmpty());
    }

    // It is a requirement like any other, so the schema version lists it with the rest
    @Test
    void readsAsARequirement()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(Requirement.class) instanceof ClassificationRequirement);
    }
}
