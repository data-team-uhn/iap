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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that writes down what a reviewer decided about one requirement.
 *
 * <p>A decision reached through a workflow already leaves two traces — the outcome on the task that carried it, and
 * wherever the process routed next — but neither says <em>what was decided about what</em>. A submission's schema
 * can ask for several approvals, and "the request was approved" cannot tell them apart. This records the decision
 * against the requirement it answers, which is what makes an approval fulfillable at all:
 * {@code Submission.getMissingRequirements} counts an {@code sch:ApprovalRequirement} as met by an approved
 * {@code sub:Review} naming it, and the form reports the same fact back to the submitter.</p>
 *
 * <p>Which requirement is named by the activity, in the definition, rather than inferred: a process may raise
 * several decisions and only the diagram knows which of them this one is. It is the same shape as the notification
 * action naming its template — configuration where the process is drawn, not a convention over node names.</p>
 *
 * <p>The outcome becomes the review's tag, so {@code approved} is what the model already reads and any other
 * outcome records a decision that did not approve. The note the decider wrote becomes a comment, because that is
 * where a submission keeps somebody's words, and a blank note leaves no comment at all rather than an empty one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordReviewHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "recordReview";

    /** The activity property naming the requirement being decided. */
    static final String REQUIREMENT = "requirement";

    /** The event entry carrying the decision. */
    static final String OUTCOME = "outcome";

    /** The event entry carrying what the decider said about it. */
    static final String OUTCOME_NOTE = "outcomeNote";

    /** Where the review records what it was filed against, named as every other such part names it. */
    private static final String FULFILLS = "fulfills";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The review workflow only applies to submissions");
        final Requirement requirement = requirement(submission, context);
        final String outcome = text(context.getEvent().get(OUTCOME));

        // A UUID rather than the reviewer's name: the same person may decide more than once, and a node name taken
        // from a user id would collide the second time
        final Resource review = context.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Review", "reviewer", context.getActor(),
                // The outcome is the tag, which is how the model reads whether an approval was granted. A decision
                // that is not `approved` is still recorded, and reads as one that did not approve
                "tags", outcome == null ? new String[0] : new String[] {outcome}));
        reference(review, requirement);

        final String note = text(context.getEvent().get(OUTCOME_NOTE));
        if (note != null) {
            context.getResourceResolver().create(review, "note",
                Map.of("jcr:primaryType", "sub:ReviewComment", "author", context.getActor(), "text", note));
        }
    }

    /**
     * The requirement this activity says it decides.
     *
     * @param submission the submission being reviewed
     * @param context the executing task's context
     * @return the requirement named by the activity
     * @throws WorkflowDefinitionException when the activity names none, or names one this submission is not asked
     */
    private Requirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws WorkflowDefinitionException
    {
        final String named = context.getActivity().get(REQUIREMENT, String.class);
        if (named == null || named.isBlank()) {
            throw new WorkflowDefinitionException(
                "The review task " + context.getActivity().getPath() + " does not say which requirement it decides");
        }
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException(
                "There is no requirement " + named + " in this request's schema"));
    }

    /**
     * Points the review at what it is about.
     *
     * @param review the review just created
     * @param requirement the requirement it decides
     * @throws PersistenceException when the reference cannot be written
     */
    private void reference(final Resource review, final Requirement requirement) throws PersistenceException
    {
        final Resource resource = review.getResourceResolver().getResource(requirement.getPath());
        if (resource == null) {
            throw new PersistenceException("Could not read the requirement being decided");
        }
        final Node reviewNode = Objects.requireNonNull(review.adaptTo(Node.class),
            "A freshly created review is always backed by a JCR node");
        try {
            // A REFERENCE cannot be written as a string through the resolver: Sling stores it as a STRING and Oak's
            // type validation refuses the commit rather than coercing it
            reviewNode.setProperty(FULFILLS, Objects.requireNonNull(resource.adaptTo(Node.class),
                "A requirement read from the schema is always backed by a JCR node"));
        } catch (final RepositoryException e) {
            // Not recorded as an error here: it is thrown on, and a failing service task is the engine's to report
            throw new PersistenceException("Could not reference the requirement", e);
        }
    }

    /**
     * One event entry as text, with a blank treated as nothing said.
     *
     * @param value whatever the event carried under that key
     * @return the text, or {@code null} when there was none
     */
    private static String text(final Object value)
    {
        return value instanceof String && !((String) value).isBlank() ? (String) value : null;
    }
}
