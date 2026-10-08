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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Feedback} and its kinds: that each reads as its own model through the common base, and
 * that what they share is read the same way for all of them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FeedbackTest
{
    private static final String PATH = "/Submissions/submission/feedback";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Review.class, Screening.class,
            Discussion.class, ReviewComment.class, Assessment.class);
    }

    @Test
    void readsEachKindAsItsOwnModel()
    {
        assertInstanceOf(Review.class, feedback("review", Review.RESOURCE_TYPE));
        assertInstanceOf(Screening.class, feedback("screening", Screening.RESOURCE_TYPE));
        assertInstanceOf(Discussion.class, feedback("discussion", Discussion.RESOURCE_TYPE));
    }

    @Test
    void listsCommentsAndTheUnresolvedOnes()
    {
        final Feedback discussion = feedback("discussion", Discussion.RESOURCE_TYPE);
        this.context.create().resource(PATH + "discussion/c1", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Who keeps the data?", "author", "a"));
        this.context.create().resource(PATH + "discussion/c2", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Answered", "author", "a",
            "resolved", true));

        assertEquals(2, discussion.getComments().size());
        assertEquals(List.of("Who keeps the data?"),
            discussion.getUnresolvedComments().stream().map(ReviewComment::getText).collect(Collectors.toList()));
    }

    @Test
    void listsOnlyAssessments()
    {
        final Feedback screening = feedback("screening", Screening.RESOURCE_TYPE);
        this.context.create().resource(PATH + "screening/privacy", Map.of(
            "sling:resourceType", Assessment.RESOURCE_TYPE, "value", "Looked at"));
        this.context.create().resource(PATH + "screening/c1", Map.of(
            "sling:resourceType", ReviewComment.RESOURCE_TYPE, "text", "Not an assessment", "author", "a"));

        assertEquals(List.of("Looked at"),
            screening.getAssessments().stream().map(Assessment::getValue).collect(Collectors.toList()));
        assertTrue(feedback("empty", Discussion.RESOURCE_TYPE).getAssessments().isEmpty());
    }

    /**
     * Creates one feedback node and reads it through the common base.
     *
     * @param name its node name
     * @param type its resource type
     * @return the model it reads as
     */
    private Feedback feedback(final String name, final String type)
    {
        final Resource resource = this.context.create().resource(PATH + name, "sling:resourceType", type);
        return resource.adaptTo(Feedback.class);
    }
}
