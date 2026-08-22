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

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Answer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Answer.class, Question.class,
            Evidence.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/answer",
            "sling:resourceType", Answer.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Answer.class));
    }

    @Test
    void exposesAnswerProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/q1",
            "sling:resourceType", Question.RESOURCE_TYPE, "text", "Does this involve human subjects?");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/q1");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission/answer", Map.of(
            "sling:resourceType", Answer.RESOURCE_TYPE,
            "question", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "value", new String[]{ "yes" }));
        final Answer answer = resource.adaptTo(Answer.class);

        assertEquals("Does this involve human subjects?", answer.getQuestion().getText());
        assertArrayEquals(new String[]{ "yes" }, answer.getValue());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/bare",
            "sling:resourceType", Answer.RESOURCE_TYPE);
        final Answer answer = resource.adaptTo(Answer.class);

        assertNotNull(answer);
        assertNull(answer.getQuestion());
        assertNull(answer.getValue());
        assertFalse(answer.isExtracted());
        assertNull(answer.getExtractedAnswer());
        assertNull(answer.getConfidence());
        assertNull(answer.getReasoning());
        assertNull(answer.getEditDistance());
        assertNull(answer.getPercentageDistance());
        assertTrue(answer.getEvidence().isEmpty());
    }

    @Test
    void exposesWhatExtractionFound()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/extracted", Map.of(
            "sling:resourceType", Answer.RESOURCE_TYPE,
            "extracted", true,
            "extractedAnswer", "42 participants",
            "confidence", 0.87,
            "reasoning", "The recruitment table gives 42 in the final column",
            "editDistance", 3L,
            "percentageDistance", 20.0));
        final Answer answer = resource.adaptTo(Answer.class);

        assertTrue(answer.isExtracted());
        assertEquals("42 participants", answer.getExtractedAnswer());
        assertEquals(0.87, answer.getConfidence());
        assertEquals("The recruitment table gives 42 in the final column", answer.getReasoning());
        assertEquals(3L, answer.getEditDistance());
        assertEquals(20.0, answer.getPercentageDistance());
    }

    @Test
    void reportsThatExtractionRanAndFoundNothing()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/empty", Map.of(
            "sling:resourceType", Answer.RESOURCE_TYPE,
            "extracted", true,
            "editDistance", -1L,
            "percentageDistance", -1.0));
        final Answer answer = resource.adaptTo(Answer.class);

        // "ran and found nothing" is the case a null extracted answer cannot tell apart from "never ran"
        assertTrue(answer.isExtracted());
        assertNull(answer.getExtractedAnswer());
        assertEquals(-1L, answer.getEditDistance());
        assertEquals(-1.0, answer.getPercentageDistance());
    }

    @Test
    void listsTheEvidenceBackingTheExtractedAnswer()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/cited",
            "sling:resourceType", Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/cited/evidence0", Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "42 participants will be recruited"));
        this.context.create().resource("/Submissions/submission/cited/evidence1", Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "of whom 42 complete the protocol"));
        // Not evidence, and must not be listed as such
        this.context.create().resource("/Submissions/submission/cited/upload",
            "sling:resourceType", "nt:file");
        final Answer answer = resource.adaptTo(Answer.class);

        final List<Evidence> evidence = answer.getEvidence();

        assertEquals(2, evidence.size());
        assertEquals("42 participants will be recruited", evidence.get(0).getQuote());
        assertEquals("of whom 42 complete the protocol", evidence.get(1).getQuote());
    }
}
