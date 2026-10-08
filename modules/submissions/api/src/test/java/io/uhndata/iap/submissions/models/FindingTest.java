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
import java.util.stream.Collectors;

import javax.jcr.ItemNotFoundException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Finding}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FindingTest
{
    private static final String PATH = "/Submissions/submission/screening/privacy/finding";

    private static final String ANSWER_PATH = "/Submissions/submission/a1";

    private static final String ANSWER_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Finding.class, Answer.class,
            Evidence.class, Comment.class);
    }

    @Test
    void exposesTheConcernAndWhatItIsAbout() throws RepositoryException
    {
        this.context.create().resource(ANSWER_PATH, "sling:resourceType", Answer.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        final Node answer = Mockito.mock(Node.class);
        Mockito.when(answer.getPath()).thenReturn(ANSWER_PATH);
        Mockito.when(session.getNodeByIdentifier(ANSWER_ID)).thenReturn(answer);
        // The link is weak, so a subject may have been removed since
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Finding.RESOURCE_TYPE, "statement", "Names are collected but not protected",
            "kind", "gap", "suggestion", "Say how names are stored", "subjects", new String[] {ANSWER_ID, GONE_ID}));
        final Finding finding = resource.adaptTo(Finding.class);

        assertEquals("Names are collected but not protected", finding.getStatement());
        assertEquals("gap", finding.getKind());
        assertEquals("Say how names are stored", finding.getSuggestion());
        assertEquals(List.of(ANSWER_PATH),
            finding.getSubjects().stream().map(EntityPart::getPath).collect(Collectors.toList()));
    }

    @Test
    void listsItsEvidenceAndComments()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Finding.RESOURCE_TYPE, "statement", "No retention period"));
        this.context.create().resource(PATH + "/e1", Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "Data is kept as needed"));
        this.context.create().resource(PATH + "/c1", Map.of(
            "sling:resourceType", Comment.RESOURCE_TYPE, "text", "It is in section 4", "author", "a"));
        final Finding finding = resource.adaptTo(Finding.class);

        assertEquals(1, finding.getEvidence().size());
        assertEquals(List.of("It is in section 4"),
            finding.getComments().stream().map(Comment::getText).collect(Collectors.toList()));
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Finding finding = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Finding.RESOURCE_TYPE, "statement", "Something")).adaptTo(Finding.class);

        assertNull(finding.getKind());
        assertNull(finding.getSuggestion());
        assertTrue(finding.getSubjects().isEmpty());
        assertTrue(finding.getEvidence().isEmpty());
        assertTrue(finding.getComments().isEmpty());
    }
}
