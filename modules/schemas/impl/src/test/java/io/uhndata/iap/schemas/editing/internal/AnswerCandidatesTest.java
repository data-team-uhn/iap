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

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.spi.Candidate;

import static org.apache.sling.api.resource.ResourceResolver.PROPERTY_RESOURCE_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerCandidates}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerCandidatesTest
{
    private static final String QUESTION = "sch/Question";

    private static final String UUID = "jcr:uuid";

    private final SlingContext context = new SlingContext();

    private final AnswerCandidates candidates = new AnswerCandidates();

    @BeforeEach
    void setUp()
    {
        final var create = this.context.create();
        create.resource("/Schemas/loan/v1", PROPERTY_RESOURCE_TYPE, "sch/SchemaVersion");
        create.resource("/Schemas/loan/v1/form", PROPERTY_RESOURCE_TYPE, "sch/FormRequirement", "label", "Loan");
        create.resource("/Schemas/loan/v1/form/what", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-what",
            "text", "What do you need?", "dataType", "text"));
        create.resource("/Schemas/loan/v1/form/what/laptop", PROPERTY_RESOURCE_TYPE, "sch/AnswerOption",
            "value", "laptop", "label", "Laptop");
        create.resource("/Schemas/loan/v1/form/what/monitor", PROPERTY_RESOURCE_TYPE, "sch/AnswerOption",
            "value", "Monitor");
        create.resource("/Schemas/loan/v1/form/howMany", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-many",
            "text", "How many?", "dataType", "long", "maxAnswers", 0L));
        create.resource("/Schemas/loan/v1/form/upload", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-file",
            "dataType", "file"));
        create.resource("/Schemas/loan/v1/form/where", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-where",
            "text", "Where?", "optionsFrom", "/Sites"));
        create.resource("/Schemas/loan/v1/form/lost", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-lost",
            "text", "Lost?", "optionsFrom", "/Nowhere"));
        create.resource("/Schemas/loan/v1/form/unsaved", PROPERTY_RESOURCE_TYPE, QUESTION, "text", "Not yet saved");
        create.resource("/Schemas/loan/v1/form/more", PROPERTY_RESOURCE_TYPE, "sch/Section", "title", "More");
        create.resource("/Schemas/loan/v1/form/more/why", Map.of(PROPERTY_RESOURCE_TYPE, QUESTION, UUID, "u-why",
            "text", "Why?"));
        create.resource("/Sites/toronto", "title", "Toronto");
        create.resource("/Sites/toronto/east", "label", "Toronto East");
        create.resource("/Sites/jcr:content");
    }

    @Test
    void servesTheAnswerSource()
    {
        assertEquals("answer", this.candidates.getSource());
    }

    @Test
    void listsTheVersionsQuestionsExceptThePartAndWhatItHolds()
    {
        final List<String> labels = labelsFor("/Schemas/loan/v1/form/more");
        assertEquals(List.of("What do you need?", "How many?", "upload", "Where?", "Lost?"), labels);
    }

    @Test
    void listsEveryQuestionWhenAskedAboutTheVersionItself()
    {
        assertEquals(List.of("What do you need?", "How many?", "upload", "Where?", "Lost?", "Why?"),
            labelsFor("/Schemas/loan/v1"));
    }

    @Test
    void namesEachQuestionByItsIdentifierAndByItsPath()
    {
        final Candidate why = candidate("/Schemas/loan/v1/form/what", "Why?");
        assertEquals("u-why", why.value());
        assertEquals(List.of("form/more/why"), why.aliases());
        // Told apart from another worded the same by its name
        assertEquals("why", why.detail());
    }

    @Test
    void saysWhatItsAnswersHold()
    {
        final Candidate what = candidate("/Schemas/loan/v1/form/more", "What do you need?");
        assertEquals(OperandType.TEXT, what.type());
        assertFalse(what.multiple());
        assertEquals(List.of(new Candidate.Choice("laptop", "Laptop"), new Candidate.Choice("Monitor", "Monitor")),
            what.choices());
        final Candidate many = candidate("/Schemas/loan/v1/form/more", "How many?");
        assertEquals(OperandType.LONG, many.type());
        assertTrue(many.multiple());
        // A file has nothing to compare
        assertNull(candidate("/Schemas/loan/v1/form/more", "upload").type());
    }

    @Test
    void takesTheChoicesFromTheItemsAQuestionNames()
    {
        assertEquals(List.of(new Candidate.Choice("/Sites/toronto", "Toronto"),
            new Candidate.Choice("/Sites/toronto/east", "Toronto East")),
            candidate("/Schemas/loan/v1/form/more", "Where?").choices());
        assertTrue(candidate("/Schemas/loan/v1/form/more", "Lost?").choices().isEmpty());
    }

    @Test
    void listsNothingOutsideASchemaVersion()
    {
        assertTrue(this.candidates.getCandidates(this.context.resourceResolver().getResource("/Sites")).isEmpty());
    }

    private List<String> labelsFor(final String part)
    {
        return this.candidates.getCandidates(this.context.resourceResolver().getResource(part)).stream()
            .map(Candidate::label).toList();
    }

    private Candidate candidate(final String part, final String label)
    {
        return this.candidates.getCandidates(this.context.resourceResolver().getResource(part)).stream()
            .filter(candidate -> candidate.label().equals(label)).findFirst().orElseThrow();
    }
}
