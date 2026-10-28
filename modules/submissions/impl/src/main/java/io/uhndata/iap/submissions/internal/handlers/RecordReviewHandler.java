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
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that writes a decision down against the requirement it decides.
 *
 * <p>A decision made in a user task routes the process, but routing does not say what was decided. A schema may ask
 * for several approvals, and only a review naming the requirement says which of them was granted. This handler
 * records one: by whoever completed the task, tagged with the task's outcome, and with the decider's note as a
 * comment. The activity names the requirement in its {@code requirement}, by name or by path, since only the
 * definition knows which decision a step makes.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordReviewHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordReview";

    /** The activity property naming the requirement decided. */
    private static final String REQUIREMENT_PARAMETER = "requirement";

    /** The event entry carrying the decision. */
    private static final String OUTCOME_PARAMETER = "outcome";

    /** The event entry carrying what the decider said about it. */
    private static final String NOTE_PARAMETER = "outcomeNote";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource host = ExecutionHost.of(context);
        final Requirement requirement = requirementDecided(SubmissionHost.read(host, HANDLER_NAME),
            context.getActivity());
        final String outcome = Payloads.requireText(context.getEvent(), OUTCOME_PARAMETER,
            "A decision is written down with the outcome it reached");
        final Resource review = createReview(context, host, outcome);
        ReferenceUtils.setReference(review, REQUIREMENT_PARAMETER, requirement);
        addNote(context, review);
    }

    /**
     * The requirement the activity says it decides, among those the submission's schema version asks.
     *
     * @param submission the submission being decided
     * @param activity the activity being performed
     * @return the requirement named by the activity
     * @throws WorkflowDefinitionException when the activity names none, or one the schema version does not ask
     */
    private static Requirement requirementDecided(final Submission submission, final Activity activity)
        throws WorkflowDefinitionException
    {
        final String named = activity.get(REQUIREMENT_PARAMETER, String.class);
        if (named == null || named.isBlank()) {
            throw new WorkflowDefinitionException("The activity " + activity.getPath()
                + " does not say which requirement it decides");
        }
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(candidate -> named.equals(candidate.getName()) || named.equals(candidate.getPath()))
            .findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("The activity " + activity.getPath() + " decides "
                + named + ", which this submission's schema version does not ask"));
    }

    private static Resource createReview(final WorkflowTaskContext context, final Resource host, final String outcome)
        throws PersistenceException
    {
        // A UUID rather than the reviewer's name: the same person may decide more than once
        return context.getResourceResolver().create(host, UUID.randomUUID().toString(), Map.of(
            PRIMARY_TYPE, "sub:Review", "reviewer", context.getActor(), "tags", new String[] {outcome}));
    }

    // What the decider said, if anything, as the review's comment
    private static void addNote(final WorkflowTaskContext context, final Resource review) throws PersistenceException
    {
        final String note = Payloads.text(context.getEvent(), NOTE_PARAMETER);
        if (note != null) {
            context.getResourceResolver().create(review, "note",
                Map.of(PRIMARY_TYPE, "sub:Comment", "author", context.getActor(), "text", note));
        }
    }
}
