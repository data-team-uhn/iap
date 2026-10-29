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
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
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

import static io.uhndata.iap.submissions.internal.CompletenessFixture.REQUIREMENT;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.SUBMISSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.SUPER_TYPE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.TYPE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.VERSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.reference;
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
    private static final String APPROVAL = "approval";

    private static final String APPROVAL_PATH = VERSION_PATH + "/" + APPROVAL;

    private static final String APPROVER = "demo-approver";

    // The keys of the wire format, written out rather than taken from the handler, so a renamed key fails here
    private static final String REQUIREMENT_KEY = "requirement";

    private static final String OUTCOME = "outcome";

    private static final String NOTE = "outcomeNote";

    private static final String APPROVED = "approved";

    private static final Map<String, String> RESOURCE_TYPES = Map.of(
        "sub:Review", Review.RESOURCE_TYPE, "sub:Comment", Comment.RESOURCE_TYPE);

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final RecordReviewHandler handler = new RecordReviewHandler();

    private Resource target;

    // What the request reads as when adapted to a node: jcr-mock answers nothing about versioning
    private Node node;

    @BeforeEach
    void setUp() throws Exception
    {
        this.node = Mockito.mock(Node.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(true);
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, ApprovalRequirement.class, Review.class, Comment.class,
            Submission.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        // Decoys before the requirement decided, so finding it is a choice rather than taking the first: another
        // approval, and a requirement of another kind
        this.context.create().resource(VERSION_PATH + "/budget", Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Budget approval"));
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        this.context.create().resource(APPROVAL_PATH, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Approval"));
        Referenceables.identify(this.context, VERSION_PATH + "/budget", APPROVAL_PATH);
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", "sam",
            "tags", new String[] {"submitted"}));
        reference(this.context, SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RecordReviewHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void recordsWhoDecided() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, APPROVED)));

        assertEquals(APPROVER, onlyReview().getReviewer());
    }

    @Test
    void pointsAtTheRequirementItDecides() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, APPROVED)));

        assertEquals(APPROVAL_PATH, present(onlyReview().getRequirement()).getPath());
    }

    @Test
    void tagsTheReviewWithTheOutcome() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, APPROVED)));

        assertTrue(onlyReview().isApproved());
    }

    @Test
    void recordsARejectionAsADecisionThatDidNotApprove() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, Review.REJECTED_TAG)));

        final Review review = onlyReview();
        assertTrue(review.isDecided());
        assertFalse(review.isApproved());
    }

    @Test
    void keepsWhatTheDeciderSaidAsAComment() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL,
            Map.of(OUTCOME, Review.REJECTED_TAG, NOTE, "Take the week after instead")));

        final List<Comment> comments = onlyReview().getComments();
        assertEquals(1, comments.size());
        assertEquals("Take the week after instead", comments.get(0).getText());
        assertEquals(APPROVER, comments.get(0).getAuthor());
    }

    @Test
    void leavesNoCommentWhenTheNoteIsBlank() throws Exception
    {
        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, APPROVED, NOTE, "  ")));

        assertTrue(onlyReview().getComments().isEmpty());
    }

    @Test
    void checksOutTheRequestBeforeRecordingTheDecision() throws Exception
    {
        // The Sling POST servlet checks in whatever it creates, and a checked-in request takes no reviews
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(false);
        Mockito.when(this.node.isNodeType("mix:versionable")).thenReturn(true);
        Mockito.when(this.node.getPath()).thenReturn(SUBMISSION_PATH);
        Mockito.when(this.node.getSession()).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        Mockito.doAnswer(invocation -> {
            assertFalse(this.target.hasChildren(), "A review was written before the request was checked out");
            return null;
        }).when(versions).checkout(SUBMISSION_PATH);

        this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, APPROVED)));

        Mockito.verify(versions).checkout(SUBMISSION_PATH);
    }

    @Test
    void refusesADecisionWithNoOutcome()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(approverCompleting(APPROVAL, Map.of())));
        assertTrue(reviews().isEmpty());
    }

    @Test
    void refusesAnOutcomeThatNamesNoStateAReviewCanBeIn()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting(APPROVAL, Map.of(OUTCOME, Tagging.UNDEFINED))));

        assertTrue(failure.getMessage().contains("names no state a review can be in"));
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhatItDecides()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting(null, Map.of(OUTCOME, APPROVED))));

        assertTrue(failure.getMessage().contains("does not say which requirement"));
    }

    @Test
    void refusesAnActivityNamingABlankRequirement()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting("  ", Map.of(OUTCOME, APPROVED))));

        assertTrue(failure.getMessage().contains("does not say which requirement"));
    }

    @Test
    void refusesAnActivityNamingARequirementTheSchemaDoesNotAsk()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting("secondApproval", Map.of(OUTCOME, APPROVED))));

        assertTrue(failure.getMessage().contains("decides secondApproval"));
    }

    @Test
    void refusesARequirementThatIsNoApproval()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting("details", Map.of(OUTCOME, APPROVED))));
    }

    @Test
    void refusesARequirementNamedByItsPath()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(approverCompleting(APPROVAL_PATH, Map.of(OUTCOME, APPROVED))));
    }

    @Test
    void refusesSomethingThatIsNotASubmission()
    {
        final Resource homepage = this.context.create().resource("/Submissions/elsewhere",
            Map.of(TYPE, "sub/SubmissionsHomepage"));
        final WorkflowTaskContext completing = new TaskContext(homepage, APPROVER,
            new WorkflowEvent("complete", Map.of(OUTCOME, APPROVED))).performing(recordingActivity(APPROVAL));

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(completing));
    }

    private Review onlyReview()
    {
        typeWhatTheHandlerWrote();
        final List<Review> reviews = reviews();
        assertEquals(1, reviews.size());
        return reviews.get(0);
    }

    private List<Review> reviews()
    {
        this.context.resourceResolver().refresh();
        return present(present(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .adaptTo(Submission.class)).getReviews();
    }

    // A mock repository does not autocreate sling:resourceType from the node type, so stamp it
    private void typeWhatTheHandlerWrote()
    {
        this.context.resourceResolver().refresh();
        stampResourceTypes(present(this.context.resourceResolver().getResource(SUBMISSION_PATH)));
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

    /**
     * The context of the service task after the approval task, completed by the approver with the given payload.
     *
     * @param requirement what the activity says it decides, or {@code null} for an activity saying nothing
     * @param payload what the completion carried
     * @return the context
     */
    private WorkflowTaskContext approverCompleting(final String requirement, final Map<String, Object> payload)
    {
        final Node asNode = this.node;
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(asNode) : super.adaptTo(type);
            }
        };
        return new TaskContext(submission, APPROVER, new WorkflowEvent("complete", payload))
            .performing(recordingActivity(requirement));
    }

    private static Activity recordingActivity(final String requirement)
    {
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.get(REQUIREMENT_KEY, String.class)).thenReturn(requirement);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/timeOffRequest/v1/recordReview");
        return activity;
    }
}
