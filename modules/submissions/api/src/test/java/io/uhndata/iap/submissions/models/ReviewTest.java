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
import io.uhndata.iap.schemas.models.ApprovalRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Review}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReviewTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Reply.class, ReviewComment.class,
            Review.class, ApprovalRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review",
            "sling:resourceType", Review.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Review.class));
    }

    @Test
    void exposesReviewProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/reb",
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE, "label", "REB approval");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/reb");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission/review", Map.of(
            "sling:resourceType", Review.RESOURCE_TYPE,
            "reviewer", "reviewer1",
            "requirement", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "status", "in-progress"));
        final Review review = resource.adaptTo(Review.class);

        assertEquals("reviewer1", review.getReviewer());
        assertEquals(ApprovalRequirement.class, review.getRequirement().getClass());
        assertEquals("REB approval", review.getRequirement().getLabel());
        assertEquals("in-progress", review.getStatus());
    }

    @Test
    void listsAllComments()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review",
            "sling:resourceType", Review.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/c1", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Resolved one", "resolved", true));
        this.context.create().resource("/Submissions/submission/review/c2", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Still open", "resolved", false));
        final Review review = resource.adaptTo(Review.class);

        final List<ReviewComment> comments = review.getComments();
        assertEquals(2, comments.size());
    }

    @Test
    void listsOnlyUnresolvedComments()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review",
            "sling:resourceType", Review.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/c1", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Resolved one", "resolved", true));
        this.context.create().resource("/Submissions/submission/review/c2", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Still open", "resolved", false));
        this.context.create().resource("/Submissions/submission/review/c3", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Also open", "resolved", false));
        final Review review = resource.adaptTo(Review.class);

        final List<ReviewComment> unresolved = review.getUnresolvedComments();

        assertEquals(2, unresolved.size());
        assertEquals("Still open", unresolved.get(0).getText());
        assertEquals("Also open", unresolved.get(1).getText());
    }

    @Test
    void reportsNoUnresolvedCommentsWhenAllResolved()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/review",
            "sling:resourceType", Review.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/review/c1", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Resolved one", "resolved", true));
        final Review review = resource.adaptTo(Review.class);

        assertTrue(review.getUnresolvedComments().isEmpty());
    }
}
