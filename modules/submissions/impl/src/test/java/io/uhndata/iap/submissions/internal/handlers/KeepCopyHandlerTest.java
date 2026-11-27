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

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

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
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.testing.PropertyTagging;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KeepCopyHandler}. A real repository, since the copy is a version. The submission's own node types
 * are not registered: plain nodes with resource types read through the same models.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class KeepCopyHandlerTest
{
    private static final String DECIDER = "reb-reviewer";

    private static final String SUBMISSION_PATH = "/Submissions/s1";

    private static final String REVIEW_PATH = SUBMISSION_PATH + "/review";

    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final KeepCopyHandler handler = new KeepCopyHandler();

    private Session session;

    private Node submission;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, SchemaVersion.class,
            ApprovalRequirement.class, Review.class, Submission.class);
        PropertyTagging.installIn(this.context);
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        final Node version = node("/Schemas/schema/v1", SchemaVersion.RESOURCE_TYPE);
        final Node reb = version.addNode("reb", "nt:unstructured");
        reb.addMixin("mix:referenceable");
        reb.setProperty(TYPE, ApprovalRequirement.RESOURCE_TYPE);
        reb.setProperty("sling:resourceSuperType", "sch/Requirement");
        this.submission = node(SUBMISSION_PATH, Submission.RESOURCE_TYPE);
        this.submission.addMixin("mix:versionable");
        this.submission.setProperty("schemaVersion", version);
        this.submission.setProperty("title", "As sent back");
        final Node review = this.submission.addNode("review", "nt:unstructured");
        review.setProperty(TYPE, Review.RESOURCE_TYPE);
        review.setProperty("reviewer", DECIDER);
        review.setProperty("requirement", reb);
        review.setProperty("tags", new String[] {"changes-requested"});
        this.session.save();
    }

    @Test
    void namesACopyOfTheSubmissionOnTheReviewOnceTheDeliveryCommits() throws Exception
    {
        final RecordingTaskContext deciding = deciding("reb");

        this.handler.execute(deciding);
        assertFalse(this.session.getNode(REVIEW_PATH).hasProperty("sentBackCopy"));
        deciding.runAfterCommit();

        final String copy = this.session.getNode(REVIEW_PATH).getProperty("sentBackCopy").getString();
        assertEquals("As sent back",
            this.session.getNode(copy).getNode("jcr:frozenNode").getProperty("title").getString());
        // Writable again, for the changes the team makes
        assertTrue(this.submission.isCheckedOut());
    }

    @Test
    void namesNoCopyOnAReviewGoneByTheTimeItIsTaken() throws Exception
    {
        final RecordingTaskContext deciding = deciding("reb");
        this.handler.execute(deciding);
        this.session.getNode(REVIEW_PATH).remove();
        this.session.save();

        deciding.runAfterCommit();

        assertFalse(this.session.nodeExists(REVIEW_PATH));
    }

    @Test
    void recordsACopyThatCannotBeTakenAndLeavesTheReviewAlone() throws Exception
    {
        final Node unversioned = node("/Submissions/s2", Submission.RESOURCE_TYPE);
        unversioned.setProperty("schemaVersion", this.session.getNode("/Schemas/schema/v1"));
        this.session.move(REVIEW_PATH, "/Submissions/s2/review");
        this.session.save();
        final RecordingTaskContext deciding = RecordingTaskContext.aimedAt(resource("/Submissions/s2"))
            .actingFor(DECIDER).forEvent("complete", Map.of()).performing(activityFor("reb")).build();

        this.handler.execute(deciding);
        deciding.runAfterCommit();

        assertFalse(this.session.getNode("/Submissions/s2/review").hasProperty("sentBackCopy"));
    }

    @Test
    void refusesWhenTheDeciderHasNoReviewOpen() throws RepositoryException
    {
        this.session.getNode(REVIEW_PATH).setProperty("reviewer", "somebody-else");
        this.session.save();

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(deciding("reb")));
    }

    @Test
    void refusesAnActivityNamingNoApprovalTheSchemaAsks()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(deciding("budget")));
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(deciding(null)));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("keepCopy", this.handler.getName());
    }

    private RecordingTaskContext deciding(final String requirement)
    {
        return RecordingTaskContext.aimedAt(resource(SUBMISSION_PATH)).actingFor(DECIDER)
            .forEvent("complete", Map.of()).performing(activityFor(requirement)).build();
    }

    private Resource resource(final String path)
    {
        return this.context.resourceResolver().getResource(path);
    }

    private static Activity activityFor(final String requirement)
    {
        final Activity activity = Mockito.mock(Activity.class);
        try {
            // It decides what it acts on as the model does
            Mockito.doCallRealMethod().when(activity).actsOn(Mockito.any());
        } catch (final WorkflowDefinitionException e) {
            throw new AssertionError("Stubbing a method never runs it", e);
        }
        Mockito.when(activity.get("requirement", String.class)).thenReturn(requirement);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/review/v1/keepCopy");
        return activity;
    }

    // A plain node at the path, with the parents it needs, read through the model of the given resource type
    private Node node(final String path, final String resourceType) throws RepositoryException
    {
        Node parent = this.session.getRootNode();
        for (final String name : path.substring(1).split("/")) {
            parent = parent.hasNode(name) ? parent.getNode(name) : parent.addNode(name, "nt:unstructured");
        }
        parent.addMixin("mix:referenceable");
        parent.setProperty(TYPE, resourceType);
        return parent;
    }
}
