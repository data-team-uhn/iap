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

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String SCHEMA_VERSION_ID = "schema-version-uuid";

    private static final String QUESTION_1_ID = "q1-uuid";

    private static final String QUESTION_2_ID = "q2-uuid";

    private static final String CONSENT_ID = "consent-uuid";

    private static final String REB_ID = "reb-uuid";

    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Submission.class, Answer.class,
            Document.class, Review.class, ReviewComment.class, SchemaVersion.class, FormRequirement.class,
            DocumentRequirement.class, ApprovalRequirement.class, Section.class, Question.class,
            SingleCondition.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.APRIL, 5, 16, 20, 0);
    }

    /**
     * Creates a schema version with one {@code FormRequirement} (a nested section holding one question, plus a
     * direct question), one {@code DocumentRequirement} and one {@code ApprovalRequirement}, and mocks a JCR
     * session resolving {@link #SCHEMA_VERSION_ID}/{@link #QUESTION_1_ID}/{@link #QUESTION_2_ID}/
     * {@link #CONSENT_ID}/{@link #REB_ID} to their respective resources.
     */
    private void createSchemaVersionWithRequirements()
        throws RepositoryException
    {
        // sling:resourceSuperType is mandatory/autocreated on sch:Requirement and sch:FormItem in the real CND;
        // sling-mock doesn't know about the CND, so it must be set explicitly here for getChildren() to match.
        this.context.create().resource("/Schemas/schema/1.0", SLING_RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form", Map.of(
            SLING_RESOURCE_TYPE, FormRequirement.RESOURCE_TYPE, "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "Application form"));
        this.context.create().resource("/Schemas/schema/1.0/form/section", Map.of(
            SLING_RESOURCE_TYPE, Section.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "title", "Section"));
        this.context.create().resource("/Schemas/schema/1.0/form/section/q1", Map.of(
            SLING_RESOURCE_TYPE, Question.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "text", "Q1"));
        this.context.create().resource("/Schemas/schema/1.0/form/q2", Map.of(
            SLING_RESOURCE_TYPE, Question.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "text", "Q2"));
        this.context.create().resource("/Schemas/schema/1.0/consent", Map.of(
            SLING_RESOURCE_TYPE, DocumentRequirement.RESOURCE_TYPE, "sling:resourceSuperType",
            Requirement.RESOURCE_TYPE, "label", "Consent"));
        this.context.create().resource("/Schemas/schema/1.0/reb", Map.of(
            SLING_RESOURCE_TYPE, ApprovalRequirement.RESOURCE_TYPE, "sling:resourceSuperType",
            Requirement.RESOURCE_TYPE, "label", "REB approval"));

        final Session session = Mockito.mock(Session.class);
        this.mockNode(session, SCHEMA_VERSION_ID, "/Schemas/schema/1.0");
        this.mockNode(session, QUESTION_1_ID, "/Schemas/schema/1.0/form/section/q1");
        this.mockNode(session, QUESTION_2_ID, "/Schemas/schema/1.0/form/q2");
        this.mockNode(session, CONSENT_ID, "/Schemas/schema/1.0/consent");
        this.mockNode(session, REB_ID, "/Schemas/schema/1.0/reb");
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            SLING_RESOURCE_TYPE, "sub/Submission");
        assertNotNull(resource.adaptTo(Submission.class));
    }

    @Test
    void exposesSubmissionProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0",
            SLING_RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("2b7de6a1-3c4d-4e5f-8a9b-fedcba098765")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission",
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
            SLING_RESOURCE_TYPE, "sub/Submission",
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
            SLING_RESOURCE_TYPE, "sub/Submission",
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
            SLING_RESOURCE_TYPE, "sub/Submission");
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
            SLING_RESOURCE_TYPE, "sub/Submission");
        this.context.create().resource("/Submissions/submission/a1", SLING_RESOURCE_TYPE, "sub/Answer");
        this.context.create().resource("/Submissions/submission/d1", SLING_RESOURCE_TYPE, "sub/Document");
        this.context.create().resource("/Submissions/submission/r1", SLING_RESOURCE_TYPE, "sub/Review");
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
            SLING_RESOURCE_TYPE, "sub/Submission");
        final Submission submission = resource.adaptTo(Submission.class);

        assertTrue(submission.getAnswers().isEmpty());
        assertTrue(submission.getDocuments().isEmpty());
        assertTrue(submission.getReviews().isEmpty());
    }

    @Test
    void reportsApprovedWhenStatusIsApproved()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            SLING_RESOURCE_TYPE, "sub/Submission", "status", "approved");
        final Submission submission = resource.adaptTo(Submission.class);

        assertTrue(submission.isApproved());
    }

    @Test
    void reportsNotApprovedForOtherStatus()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            SLING_RESOURCE_TYPE, "sub/Submission", "status", "in-review");
        final Submission submission = resource.adaptTo(Submission.class);

        assertFalse(submission.isApproved());
    }

    @Test
    void aggregatesUnresolvedCommentsAcrossReviews()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            SLING_RESOURCE_TYPE, "sub/Submission");
        this.context.create().resource("/Submissions/submission/r1", SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/r1/c1", Map.of(
            SLING_RESOURCE_TYPE, ReviewComment.RESOURCE_TYPE, "text", "From r1", "author", "reviewer1",
            "resolved", false));
        this.context.create().resource("/Submissions/submission/r2", SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/r2/c2", Map.of(
            SLING_RESOURCE_TYPE, ReviewComment.RESOURCE_TYPE, "text", "From r2", "author", "reviewer2",
            "resolved", false));
        this.context.create().resource("/Submissions/submission/r2/c3", Map.of(
            SLING_RESOURCE_TYPE, ReviewComment.RESOURCE_TYPE, "text", "Resolved", "author", "reviewer2",
            "resolved", true));
        final Submission submission = resource.adaptTo(Submission.class);

        assertEquals(2, submission.getUnresolvedComments().size());
    }

    @Test
    void reportsNoMissingRequirementsWhenAllFulfilled()
        throws RepositoryException
    {
        this.createSchemaVersionWithRequirements();
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission", "schemaVersion", SCHEMA_VERSION_ID));
        this.context.create().resource("/Submissions/submission/a1", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_1_ID, "value",
            new String[]{ "yes" }));
        this.context.create().resource("/Submissions/submission/a2", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_2_ID, "value", new String[]{ "no" }));
        // An answer with no question set is ignored rather than breaking the fulfillment check.
        this.context.create().resource("/Submissions/submission/a3",
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/d1", Map.of(
            SLING_RESOURCE_TYPE, Document.RESOURCE_TYPE, "fulfills", CONSENT_ID));
        this.context.create().resource("/Submissions/submission/r1", Map.of(
            SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE, "requirement", REB_ID, "status", "approved"));
        final Submission submission = resource.adaptTo(Submission.class);

        assertTrue(submission.getMissingRequirements().isEmpty());
    }

    @Test
    void reportsMissingFormRequirementWhenAQuestionIsUnanswered()
        throws RepositoryException
    {
        this.createSchemaVersionWithRequirements();
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission", "schemaVersion", SCHEMA_VERSION_ID));
        // The nested section's question is answered; the direct question (q2) has an empty value, which
        // doesn't count as answered.
        this.context.create().resource("/Submissions/submission/a1", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_1_ID, "value",
            new String[]{ "yes" }));
        this.context.create().resource("/Submissions/submission/a2", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_2_ID, "value", new String[0]));
        this.context.create().resource("/Submissions/submission/d1", Map.of(
            SLING_RESOURCE_TYPE, Document.RESOURCE_TYPE, "fulfills", CONSENT_ID));
        this.context.create().resource("/Submissions/submission/r1", Map.of(
            SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE, "requirement", REB_ID, "status", "approved"));
        final Submission submission = resource.adaptTo(Submission.class);

        final List<Requirement> missing = submission.getMissingRequirements();

        assertEquals(1, missing.size());
        assertEquals(FormRequirement.class, missing.get(0).getClass());
    }

    @Test
    void reportsMissingDocumentRequirementWhenNoDocumentIsAttached()
        throws RepositoryException
    {
        this.createSchemaVersionWithRequirements();
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission", "schemaVersion", SCHEMA_VERSION_ID));
        this.context.create().resource("/Submissions/submission/a1", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_1_ID, "value",
            new String[]{ "yes" }));
        this.context.create().resource("/Submissions/submission/a2", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_2_ID, "value", new String[]{ "no" }));
        // This document fulfills a different requirement (the REB approval), not the consent form.
        this.context.create().resource("/Submissions/submission/d1", Map.of(
            SLING_RESOURCE_TYPE, Document.RESOURCE_TYPE, "fulfills", REB_ID));
        this.context.create().resource("/Submissions/submission/r1", Map.of(
            SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE, "requirement", REB_ID, "status", "approved"));
        final Submission submission = resource.adaptTo(Submission.class);

        final List<Requirement> missing = submission.getMissingRequirements();

        assertEquals(1, missing.size());
        assertEquals(DocumentRequirement.class, missing.get(0).getClass());
    }

    @Test
    void reportsMissingApprovalRequirementWhenReviewIsNotApproved()
        throws RepositoryException
    {
        this.createSchemaVersionWithRequirements();
        final Resource resource = this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission", "schemaVersion", SCHEMA_VERSION_ID));
        this.context.create().resource("/Submissions/submission/a1", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_1_ID, "value",
            new String[]{ "yes" }));
        this.context.create().resource("/Submissions/submission/a2", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_2_ID, "value", new String[]{ "no" }));
        this.context.create().resource("/Submissions/submission/d1", Map.of(
            SLING_RESOURCE_TYPE, Document.RESOURCE_TYPE, "fulfills", CONSENT_ID));
        this.context.create().resource("/Submissions/submission/r1", Map.of(
            SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE, "requirement", REB_ID, "status", "in-progress"));
        // This review is approved, but addresses a different requirement (the consent document).
        this.context.create().resource("/Submissions/submission/r2", Map.of(
            SLING_RESOURCE_TYPE, Review.RESOURCE_TYPE, "requirement", CONSENT_ID, "status", "approved"));
        final Submission submission = resource.adaptTo(Submission.class);

        final List<Requirement> missing = submission.getMissingRequirements();

        assertEquals(1, missing.size());
        assertEquals(ApprovalRequirement.class, missing.get(0).getClass());
    }

    @Test
    void returnsEmptyMissingRequirementsWhenSchemaVersionIsUnresolvable()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission",
            SLING_RESOURCE_TYPE, "sub/Submission");
        final Submission submission = resource.adaptTo(Submission.class);

        assertTrue(submission.getMissingRequirements().isEmpty());
    }

    /**
     * Registers a {@link ConditionEvaluator} treating any present condition as unsatisfied, so tests can toggle
     * "doesn't apply" per requirement/item simply by giving it a condition child.
     */
    private void registerConditionEvaluator()
    {
        this.context.registerService(ConditionEvaluator.class, new ConditionEvaluator()
        {
            @Override
            public boolean isSatisfied(final Condition condition, final Resource context)
            {
                return condition == null;
            }

            @Override
            public boolean applies(final Conditionable conditionable, final Resource context)
            {
                return conditionable.getCondition() == null;
            }
        });
    }

    private Submission createBareSubmission()
    {
        return this.context.create().resource("/Submissions/submission", Map.of(
            SLING_RESOURCE_TYPE, "sub/Submission", "schemaVersion", SCHEMA_VERSION_ID))
            .adaptTo(Submission.class);
    }

    @Test
    void consultsTheConditionEvaluatorWhenAvailable()
        throws RepositoryException
    {
        this.registerConditionEvaluator();
        this.createSchemaVersionWithRequirements();
        final Submission submission = this.createBareSubmission();

        // No conditions anywhere: everything applies, nothing is fulfilled.
        assertEquals(3, submission.getMissingRequirements().size());
    }

    @Test
    void skipsRequirementsWhoseConditionDoesNotHold()
        throws RepositoryException
    {
        this.registerConditionEvaluator();
        this.createSchemaVersionWithRequirements();
        this.context.create().resource("/Schemas/schema/1.0/consent/cond:condition", Map.of(
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, "comparator", "equals"));
        final Submission submission = this.createBareSubmission();

        final List<Requirement> missing = submission.getMissingRequirements();

        // The consent document requirement doesn't apply; the form and approval are still missing.
        assertEquals(2, missing.size());
        assertEquals(FormRequirement.class, missing.get(0).getClass());
        assertEquals(ApprovalRequirement.class, missing.get(1).getClass());
    }

    @Test
    void skipsSectionsWhoseConditionDoesNotHold()
        throws RepositoryException
    {
        this.registerConditionEvaluator();
        this.createSchemaVersionWithRequirements();
        this.context.create().resource("/Schemas/schema/1.0/form/section/cond:condition", Map.of(
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, "comparator", "equals"));
        final Submission submission = this.createBareSubmission();
        // Only the direct question (q2) is answered; q1 sits in the non-applicable section.
        this.context.create().resource("/Submissions/submission/a2", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_2_ID, "value", new String[]{ "no" }));

        final List<Requirement> missing = submission.getMissingRequirements();

        // The form requirement is fulfilled without q1; only the document and approval are missing.
        assertEquals(2, missing.size());
        assertEquals(DocumentRequirement.class, missing.get(0).getClass());
        assertEquals(ApprovalRequirement.class, missing.get(1).getClass());
    }

    @Test
    void skipsQuestionsWhoseConditionDoesNotHold()
        throws RepositoryException
    {
        this.registerConditionEvaluator();
        this.createSchemaVersionWithRequirements();
        this.context.create().resource("/Schemas/schema/1.0/form/q2/cond:condition", Map.of(
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, "comparator", "equals"));
        final Submission submission = this.createBareSubmission();
        // Only the nested question (q1) is answered; q2 doesn't apply.
        this.context.create().resource("/Submissions/submission/a1", Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_1_ID, "value",
            new String[]{ "yes" }));

        final List<Requirement> missing = submission.getMissingRequirements();

        assertEquals(2, missing.size());
        assertEquals(DocumentRequirement.class, missing.get(0).getClass());
        assertEquals(ApprovalRequirement.class, missing.get(1).getClass());
    }
}
