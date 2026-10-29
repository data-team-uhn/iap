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
import java.util.Objects;
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records a decision as a review of the approval requirement it decides. The review is by
 * whoever completed the task, tagged with the task's outcome, with the decider's note as a comment. The activity
 * names the requirement in its {@code requirement}.
 *
 * <p>It reads the decision from the completion that reached it, so it has to follow the user task that decides
 * directly, with no join or timer between them.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordReviewHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordReview";

    /** The activity property naming the approval requirement decided. */
    private static final String REQUIREMENT_PARAMETER = "requirement";

    /** The event entry carrying the decision. */
    private static final String OUTCOME_PARAMETER = "outcome";

    /** The event entry carrying what the decider said about it. */
    private static final String NOTE_PARAMETER = "outcomeNote";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String REVIEW_NODE_TYPE = "sub:Review";

    private static final String REVIEWER_PROPERTY = "reviewer";

    /** The review property pointing at the requirement it decides. */
    private static final String REQUIREMENT_PROPERTY = "requirement";

    private static final String NOTE_NODE = "note";

    private static final String COMMENT_NODE_TYPE = "sub:Comment";

    private static final String AUTHOR_PROPERTY = "author";

    private static final String TEXT_PROPERTY = "text";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource host = ExecutionHost.of(context);
        final ApprovalRequirement requirement = requirementDecided(SubmissionHost.read(host, HANDLER_NAME),
            context.getActivity());
        final String outcome = Payloads.requireText(context.getEvent(), OUTCOME_PARAMETER,
            "A decision is written down with the outcome it reached");
        VersioningUtils.checkOut(host);
        final Resource review = recordReview(context, host, requirement, outcome);
        final String note = Payloads.text(context.getEvent(), NOTE_PARAMETER);
        if (note != null) {
            commentOn(review, context.getActor(), note);
        }
    }

    /**
     * The approval requirement the activity says it decides, by name, among those the submission's schema version
     * asks. A name rather than a path, so that one workflow serves every schema version pinning it.
     *
     * @param submission the submission being decided
     * @param activity the activity being performed
     * @return the requirement named by the activity
     * @throws WorkflowDefinitionException when the activity names none, or no approval the schema version asks
     */
    private static ApprovalRequirement requirementDecided(final Submission submission, final Activity activity)
        throws WorkflowDefinitionException
    {
        final String named = activity.get(REQUIREMENT_PARAMETER, String.class);
        if (named == null || named.isBlank()) {
            throw new WorkflowDefinitionException("The activity " + activity.getPath()
                + " does not say which requirement it decides");
        }
        return submission.getSchemaVersion().getApprovalRequirements().stream()
            .filter(candidate -> named.equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("The activity " + activity.getPath() + " decides "
                + named + ", which is no approval this submission's schema version asks"));
    }

    /**
     * Creates the review: by whoever completed the task, pointing at the requirement, and tagged with the outcome.
     *
     * @param context the executing task's context
     * @param host the submission the review belongs to
     * @param requirement the requirement decided
     * @param outcome the decision, which must name a state a review can be in
     * @return the review
     * @throws WorkflowDefinitionException when the outcome names no such state
     * @throws PersistenceException when the review cannot be written
     */
    private static Resource recordReview(final WorkflowTaskContext context, final Resource host,
        final ApprovalRequirement requirement, final String outcome)
        throws WorkflowDefinitionException, PersistenceException
    {
        // A UUID rather than the reviewer's name: the same person may decide more than once
        final Resource review = context.getResourceResolver().create(host, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE_PROPERTY, REVIEW_NODE_TYPE, REVIEWER_PROPERTY, context.getActor()));
        ReferenceUtils.setReference(review, REQUIREMENT_PROPERTY, requirement);
        try {
            Objects.requireNonNull(review.adaptTo(Taggable.class), "A review can be tagged").tag(outcome, true);
        } catch (final IllegalArgumentException e) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " records the outcome " + outcome + ", which names no state a review can be in");
        }
        return review;
    }

    private static void commentOn(final Resource review, final String author, final String note)
        throws PersistenceException
    {
        review.getResourceResolver().create(review, NOTE_NODE,
            Map.of(PRIMARY_TYPE_PROPERTY, COMMENT_NODE_TYPE, AUTHOR_PROPERTY, author, TEXT_PROPERTY, note));
    }
}
