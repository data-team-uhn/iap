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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReviewComment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReviewCommentTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Reply.class, ReviewComment.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment",
            "sling:resourceType", ReviewComment.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(ReviewComment.class));
    }

    @Test
    void exposesReviewCommentProperties()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE,
            "text", "Please clarify the consent process",
            "author", "reviewer1",
            "subject", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "selectionStart", "page=2;offset=120",
            "selectionEnd", "page=2;offset=180",
            "resolved", false));
        final ReviewComment comment = resource.adaptTo(ReviewComment.class);

        assertEquals("Please clarify the consent process", comment.getText());
        assertEquals("reviewer1", comment.getAuthor());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", comment.getSubject());
        assertEquals("page=2;offset=120", comment.getSelectionStart());
        assertEquals("page=2;offset=180", comment.getSelectionEnd());
        assertFalse(comment.isResolved());
    }

    @Test
    void listsReplies()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/comment",
            "sling:resourceType", ReviewComment.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/comment/r1",
            "sling:resourceType", Reply.RESOURCE_TYPE, "text", "First reply");
        this.context.create().resource("/Submissions/submission/review/comment/r2",
            "sling:resourceType", Reply.RESOURCE_TYPE, "text", "Second reply");
        final ReviewComment comment = resource.adaptTo(ReviewComment.class);

        final List<Reply> replies = comment.getReplies();

        assertEquals(2, replies.size());
        assertEquals("First reply", replies.get(0).getText());
        assertEquals("Second reply", replies.get(1).getText());
    }

    @Test
    void listsNoRepliesWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review/empty",
            "sling:resourceType", ReviewComment.RESOURCE_TYPE);
        final ReviewComment comment = resource.adaptTo(ReviewComment.class);

        assertTrue(comment.getReplies().isEmpty());
    }
}
