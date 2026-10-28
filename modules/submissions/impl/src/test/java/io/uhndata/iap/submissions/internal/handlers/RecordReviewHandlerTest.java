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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
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
import io.uhndata.iap.submissions.internal.Referenceables;
import io.uhndata.iap.submissions.internal.Tagging;
import io.uhndata.iap.submissions.models.Comment;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String APPROVAL = "approval";

    private static final String APPROVAL_PATH = VERSION_PATH + "/" + APPROVAL;

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String APPROVER = "demo-approver";

    private static final String OUTCOME = "outcome";

    private static final String APPROVED = "approved";

    private static final Map<String, String> RESOURCE_TYPES = Map.of(
        "sub:Review", Review.RESOURCE_TYPE, "sub:Comment", Comment.RESOURCE_TYPE);

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordReviewHandler handler = new RecordReviewHandler();

    private Resource target;

    @BeforeEach
    void setUp() throws RepositoryException, PersistenceException
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, ApprovalRequirement.class, Review.class, Comment.class,
            Submission.class, Activity.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource(APPROVAL_PATH, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Approval"));
        // A second requirement, so that "the one it names" is a real choice rather than the only child
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        Referenceables.identify(this.context, APPROVAL_PATH);
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", "sam",
            "tags", new String[] {"submitted"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RecordReviewHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void recordsTheDecisionAgainstTheRequirementItDecides() throws Exception
    {
        this.handler.execute(deciding(APPROVAL, Map.of(OUTCOME, APPROVED)));

        final Review review = onlyReview();
        assertEquals(APPROVER, review.getReviewer());
        assertEquals(APPROVAL_PATH, present(review.getRequirement()).getPath());
        assertTrue(review.isApproved());
    }

    @Test
    void takesARequirementNamedByItsPath() throws Exception
    {
        this.handler.execute(deciding(APPROVAL_PATH, Map.of(OUTCOME, APPROVED)));

        assertEquals(APPROVAL_PATH, present(onlyReview().getRequirement()).getPath());
    }

    @Test
    void recordsARejectionAsADecisionThatDidNotApprove() throws Exception
    {
        this.handler.execute(deciding(APPROVAL, Map.of(OUTCOME, Review.REJECTED_TAG)));

        final Review review = onlyReview();
        assertTrue(review.isDecided());
        assertFalse(review.isApproved());
    }

    @Test
    void keepsWhatTheDeciderSaidAsAComment() throws Exception
    {
        this.handler.execute(deciding(APPROVAL,
            Map.of(OUTCOME, Review.REJECTED_TAG, "outcomeNote", "Take the week after instead")));

        final List<Comment> comments = onlyReview().getComments();
        assertEquals(1, comments.size());
        assertEquals("Take the week after instead", comments.get(0).getText());
        assertEquals(APPROVER, comments.get(0).getAuthor());
    }

    @Test
    void leavesNoCommentWhenNothingWasSaid() throws Exception
    {
        // A form posts an untouched field as blank
        this.handler.execute(deciding(APPROVAL, Map.of(OUTCOME, APPROVED, "outcomeNote", "  ")));

        assertTrue(onlyReview().getComments().isEmpty());
    }

    @Test
    void refusesADecisionWithNoOutcome()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(deciding(APPROVAL, Map.of())));
        assertTrue(reviews().isEmpty());
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhatItDecides()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(deciding(null, Map.of(OUTCOME, APPROVED))));

        assertTrue(failure.getMessage().contains("does not say which requirement"));
    }

    @Test
    void refusesAnActivityNamingARequirementTheSchemaDoesNotAsk()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(deciding("secondApproval", Map.of(OUTCOME, APPROVED))));

        assertTrue(failure.getMessage().contains("decides secondApproval"));
    }

    private Review onlyReview()
    {
        final List<Review> reviews = reviews();
        assertEquals(1, reviews.size());
        return reviews.get(0);
    }

    // With the sling:resourceType a real repository autocreates from the node type and a mock repository does not
    private List<Review> reviews()
    {
        this.context.resourceResolver().refresh();
        final Resource submission = present(this.context.resourceResolver().getResource(SUBMISSION_PATH));
        stampResourceTypes(submission);
        return present(submission.adaptTo(Submission.class)).getReviews();
    }

    private void stampResourceTypes(final Resource resource)
    {
        final String type = RESOURCE_TYPES.get(resource.getValueMap().get("jcr:primaryType", String.class));
        if (type != null && resource.getValueMap().get(TYPE) == null) {
            try {
                present(resource.adaptTo(ModifiableValueMap.class)).put(TYPE, type);
                this.context.resourceResolver().commit();
            } catch (final PersistenceException e) {
                throw new IllegalStateException(e);
            }
        }
        resource.getChildren().forEach(this::stampResourceTypes);
    }

    private static <T> T present(final T found)
    {
        assertNotNull(found);
        return found;
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            present(from.adaptTo(Node.class)).setProperty(property,
                present(this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The context of the service task after an approval task, completed with the given payload.
     *
     * @param requirement what the activity says it decides, or {@code null} for an activity saying nothing
     * @param payload what the completion carried
     * @return the context
     */
    private WorkflowTaskContext deciding(final String requirement, final Map<String, Object> payload)
    {
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.get("requirement", String.class)).thenReturn(requirement);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/timeOffRequest/v1/recordReview");
        return new TaskContext(this.target, APPROVER, new WorkflowEvent("complete", payload)).performing(activity);
    }
}
