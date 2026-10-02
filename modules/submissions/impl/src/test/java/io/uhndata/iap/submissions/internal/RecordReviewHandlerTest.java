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
package io.uhndata.iap.submissions.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.ReviewComment;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecordReviewHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordReviewHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String APPROVAL_PATH = VERSION_PATH + "/approval";

    private static final String SUBMISSION_PATH = "/Submissions/tor1";

    private static final String APPROVER = "priya";

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordReviewHandler handler = new RecordReviewHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, ApprovalRequirement.class, Review.class,
            ReviewComment.class, Submission.class, Activity.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(APPROVAL_PATH, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Approval"));
        // A second requirement, so that "the one it names" is a real choice rather than the only child
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", "sam",
            "tags", new String[] {"submitted"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RecordReviewHandler.NAME, this.handler.getName());
    }

    @Test
    void recordsTheDecisionAgainstTheRequirementItDecides() throws Exception
    {
        this.handler.execute(context("approval", Map.of("outcome", "approved")));

        final Review review = onlyReview();
        assertEquals(APPROVER, review.getReviewer());
        // The requirement, so that a schema asking for several approvals can tell them apart
        assertEquals(APPROVAL_PATH, Objects.requireNonNull(review.getRequirement()).getPath());
        // The outcome as the tag, which is what the model reads to decide whether the approval was granted
        assertTrue(review.isApproved());
    }

    @Test
    void takesARequirementNamedByItsFullPath() throws Exception
    {
        this.handler.execute(context(APPROVAL_PATH, Map.of("outcome", "approved")));

        assertEquals(APPROVAL_PATH, Objects.requireNonNull(onlyReview().getRequirement()).getPath());
    }

    @Test
    void recordsADecisionThatDidNotApprove() throws Exception
    {
        // A refusal is a decision and is written down as one: the tag says what it was, and the model reads the
        // absence of `approved` as an approval not granted
        this.handler.execute(context("approval", Map.of("outcome", "rejected")));

        assertEquals(false, onlyReview().isApproved());
    }

    @Test
    void keepsWhatTheDeciderSaidAsAComment() throws Exception
    {
        this.handler.execute(context("approval",
            Map.of("outcome", "rejected", "outcomeNote", "Take the week after instead")));

        final List<ReviewComment> comments = onlyReview().getComments();
        assertEquals(1, comments.size());
        assertEquals("Take the week after instead", comments.get(0).getText());
        assertEquals(APPROVER, comments.get(0).getAuthor());
    }

    @Test
    void leavesNoCommentWhenNothingWasSaid() throws Exception
    {
        // A form posts an untouched field as "", and a comment holding nothing would read as a decision explained
        // badly rather than one not explained at all
        this.handler.execute(context("approval", Map.of("outcome", "approved", "outcomeNote", "  ")));

        assertTrue(onlyReview().getComments().isEmpty());
    }

    @Test
    void recordsADecisionThatCarriedNoOutcomeAtAll() throws Exception
    {
        // Nothing in the engine demands that a task carry an outcome, so a review with no tag is possible and is
        // recorded rather than refused: it says somebody looked, and nothing more
        this.handler.execute(context("approval", Map.of()));

        assertEquals(false, onlyReview().isApproved());
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhatItDecides()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(null, Map.of("outcome", "approved"))));

        assertTrue(failure.getMessage().contains("does not say which requirement"));
    }

    @Test
    void refusesAnActivityNamingSomethingThisRequestIsNotAsked()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context("secondApproval", Map.of("outcome", "approved"))));

        assertTrue(failure.getMessage().contains("no requirement secondApproval"));
    }

    @Test
    void reportsARequirementItCannotRead()
    {
        // The requirement is found through the schema and read back through the session doing the writing; a
        // session that cannot see it has to fail as a persistence problem the engine knows how to translate
        final ResourceResolver blind = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return APPROVAL_PATH.equals(path) ? null : super.getResource(path);
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context("approval", Map.of("outcome", "approved"), blind)));
        assertTrue(failure.getMessage().contains("Could not read"));
    }

    @Test
    void translatesARepositoryFailureIntoAPersistenceOne() throws Exception
    {
        // The review is created but cannot be adapted to a node, so what it decides cannot be recorded. That has to
        // reach the engine as a persistence problem rather than as a raw repository error escaping a handler
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                return new ResourceWrapper(super.create(parent, name, properties))
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context("approval", Map.of("outcome", "approved"), sabotaged)));
        assertTrue(failure.getMessage().contains("Could not reference"));
    }

    /**
     * The one review the handler has just written, with the {@code sling:resourceType} a real repository
     * autocreates from the node type and a mock repository does not — the handler cannot stamp it itself, the
     * property is protected.
     *
     * @return the review
     */
    private Review onlyReview()
    {
        this.context.resourceResolver().refresh();
        final Resource submission = Objects.requireNonNull(
            this.context.resourceResolver().getResource(SUBMISSION_PATH));
        submission.getChildren().forEach(child -> {
            stamp(child, "sub:Review", Review.RESOURCE_TYPE);
            child.getChildren().forEach(grandchild ->
                stamp(grandchild, "sub:ReviewComment", ReviewComment.RESOURCE_TYPE));
        });
        final List<Review> reviews = Objects.requireNonNull(submission.adaptTo(Submission.class)).getReviews();
        assertEquals(1, reviews.size());
        return reviews.get(0);
    }

    /**
     * Gives one node the resource type its primary type would have autocreated.
     *
     * @param resource the node to stamp
     * @param primaryType the primary type it has to carry for this to apply
     * @param resourceType the resource type to give it
     */
    private void stamp(final Resource resource, final String primaryType, final String resourceType)
    {
        final ValueMap properties = resource.getValueMap();
        if (primaryType.equals(properties.get("jcr:primaryType", String.class)) && properties.get(TYPE) == null) {
            Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class)).put(TYPE, resourceType);
            try {
                this.context.resourceResolver().commit();
            } catch (final PersistenceException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private WorkflowTaskContext context(final String requirement, final Map<String, Object> payload)
    {
        return context(requirement, payload, this.context.resourceResolver());
    }

    private WorkflowTaskContext context(final String requirement, final Map<String, Object> payload,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent("decide", payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.get("requirement", String.class)).thenReturn(requirement);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/timeOffRequest/v1/recordDecision");
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return submission;
            }

            @Override
            public String getActor()
            {
                return APPROVER;
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return event;
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public Object getVariable(final String name)
            {
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            final Node source = Objects.requireNonNull(from.adaptTo(Node.class));
            source.setProperty(property, Objects.requireNonNull(
                this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }
}
