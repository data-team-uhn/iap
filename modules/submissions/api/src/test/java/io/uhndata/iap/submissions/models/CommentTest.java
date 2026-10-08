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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Comment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CommentTest
{
    private static final String ANSWER_PATH = "/Submissions/submission/a1";

    private static final String ANSWER_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Reply.class, Comment.class, Answer.class,
            Context.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment",
            "sling:resourceType", Comment.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Comment.class));
    }

    @Test
    void exposesCommentProperties() throws RepositoryException
    {
        this.context.create().resource(ANSWER_PATH, "sling:resourceType", Answer.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        final Node answer = Mockito.mock(Node.class);
        Mockito.when(answer.getPath()).thenReturn(ANSWER_PATH);
        Mockito.when(session.getNodeByIdentifier(ANSWER_ID)).thenReturn(answer);
        // The link is weak, so a subject may have been removed since
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment", Map.of(
            "sling:resourceType", Comment.RESOURCE_TYPE,
            "text", "Please clarify the consent process",
            "author", "reviewer1",
            "subjects", new String[] {ANSWER_ID, GONE_ID},
            "kind", "gap",
            "suggestion", "Attach the consent form",
            "resolved", false));
        final Comment comment = resource.adaptTo(Comment.class);

        assertEquals("Please clarify the consent process", comment.getText());
        assertEquals("reviewer1", comment.getAuthor());
        assertEquals(List.of(ANSWER_PATH),
            comment.getSubjects().stream().map(EntityPart::getPath).collect(Collectors.toList()));
        assertEquals("gap", comment.getKind());
        assertEquals("Attach the consent form", comment.getSuggestion());
        assertFalse(comment.isResolved());
    }

    @Test
    void listsReplies()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment",
            "sling:resourceType", Comment.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/comment/r1",
            "sling:resourceType", Reply.RESOURCE_TYPE, "text", "First reply");
        this.context.create().resource("/Submissions/submission/review/comment/r2",
            "sling:resourceType", Reply.RESOURCE_TYPE, "text", "Second reply");
        final Comment comment = resource.adaptTo(Comment.class);

        final List<Reply> replies = comment.getReplies();

        assertEquals(2, replies.size());
        assertEquals("First reply", replies.get(0).getText());
        assertEquals("Second reply", replies.get(1).getText());
    }

    @Test
    void listsTheContextItQuotes()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment",
            "sling:resourceType", Comment.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/comment/e1",
            "sling:resourceType", Context.RESOURCE_TYPE, "quote", "Data is kept as needed");
        this.context.create().resource("/Submissions/submission/review/comment/r1",
            "sling:resourceType", Reply.RESOURCE_TYPE, "text", "Not a passage");

        assertEquals(List.of("Data is kept as needed"), resource.adaptTo(Comment.class).getContext().stream()
            .map(Context::getQuote).collect(Collectors.toList()));
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Comment comment = this.context.create().resource("/Submissions/submission/review/plain",
            "sling:resourceType", Comment.RESOURCE_TYPE).adaptTo(Comment.class);

        assertTrue(comment.getSubjects().isEmpty());
        assertNull(comment.getKind());
        assertNull(comment.getSuggestion());
        assertTrue(comment.getContext().isEmpty());
    }

    @Test
    void listsNoRepliesWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/empty",
            "sling:resourceType", Comment.RESOURCE_TYPE);
        final Comment comment = resource.adaptTo(Comment.class);

        assertTrue(comment.getReplies().isEmpty());
    }
}
