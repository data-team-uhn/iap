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

import java.util.Calendar;
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
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.SchemaVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Submission}, including the properties it inherits from
 * {@link io.uhndata.iap.entities.models.Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Submission.class, Answer.class,
            Document.class, Review.class, SchemaVersion.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.APRIL, 5, 16, 20, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            "sling:resourceType", "sub/Submission");
        assertNotNull(resource.adaptTo(Submission.class));
    }

    @Test
    void exposesSubmissionProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0",
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE, "version", "1.0");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("2b7de6a1-3c4d-4e5f-8a9b-fedcba098765")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            "sling:resourceType", "sub/Submission",
            "title", "Effects of caffeine on code quality",
            "schemaVersion", "2b7de6a1-3c4d-4e5f-8a9b-fedcba098765",
            "status", "in-review"));
        final Submission submission = resource.adaptTo(Submission.class);

        assertEquals("Effects of caffeine on code quality", submission.getTitle());
        assertEquals("1.0", submission.getSchemaVersion().getVersion());
        assertEquals("in-review", submission.getStatus());
    }

    @Test
    void inheritsEntityAndContentProperties()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            "sling:resourceType", "sub/Submission",
            "jcr:uuid", "9c8b7a65-4d3e-2f10-b1a2-0123456789ab",
            "jcr:created", this.created,
            "jcr:createdBy", "bob"));
        final Submission submission = resource.adaptTo(Submission.class);

        assertEquals("/Submissions/submission", submission.getPath());
        assertEquals("submission", submission.getName());
        assertEquals("sub/Submission", submission.getType());
        assertEquals("9c8b7a65-4d3e-2f10-b1a2-0123456789ab", submission.getIdentifier());
        assertEquals(this.created, submission.getCreated());
        assertEquals("bob", submission.getCreatedBy());
    }

    @Test
    void adaptsToParentModels()
    {
        // A submission node can also be wrapped by the parent models, letting callers work with it
        // through the base models when they only need the generic properties.
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            "sling:resourceType", "sub/Submission",
            "jcr:uuid", "9c8b7a65-4d3e-2f10-b1a2-0123456789ab"));

        final Entity entity = resource.adaptTo(Entity.class);
        assertNotNull(entity);
        assertEquals(Entity.class, entity.getClass());
        assertEquals("9c8b7a65-4d3e-2f10-b1a2-0123456789ab", entity.getIdentifier());

        final Content content = resource.adaptTo(Content.class);
        assertNotNull(content);
        assertEquals(Content.class, content.getClass());
        assertEquals("sub/Submission", content.getType());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        // The status and schemaVersion properties are mandatory/autocreated at the JCR level,
        // but the model itself must not fail on a resource that lacks them.
        final Resource resource = this.context.create().resource("/Submissions/bare",
            "sling:resourceType", "sub/Submission");
        final Submission submission = resource.adaptTo(Submission.class);

        assertNotNull(submission);
        assertNull(submission.getTitle());
        assertNull(submission.getSchemaVersion());
        assertNull(submission.getStatus());
    }

    @Test
    void listsAnswersDocumentsAndReviews()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            "sling:resourceType", "sub/Submission");
        this.context.create().resource("/Submissions/submission/a1", "sling:resourceType", "sub/Answer");
        this.context.create().resource("/Submissions/submission/d1", "sling:resourceType", "sub/Document");
        this.context.create().resource("/Submissions/submission/r1", "sling:resourceType", "sub/Review");
        final Submission submission = resource.adaptTo(Submission.class);

        assertEquals(1, submission.getAnswers().size());
        assertEquals("a1", submission.getAnswers().get(0).getName());
        assertEquals(1, submission.getDocuments().size());
        assertEquals("d1", submission.getDocuments().get(0).getName());
        assertEquals(1, submission.getReviews().size());
        assertEquals("r1", submission.getReviews().get(0).getName());
    }

    @Test
    void listsNoChildrenWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Submissions/empty",
            "sling:resourceType", "sub/Submission");
        final Submission submission = resource.adaptTo(Submission.class);

        assertTrue(submission.getAnswers().isEmpty());
        assertTrue(submission.getDocuments().isEmpty());
        assertTrue(submission.getReviews().isEmpty());
    }
}
