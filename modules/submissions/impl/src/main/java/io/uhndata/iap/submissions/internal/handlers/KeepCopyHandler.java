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

import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that keeps a copy of a submission as a reviewer sent it back for changes, for whoever compares the
 * resubmission with what was sent back. Once the delivery commits, it records a version of the submission, with its
 * answers and the document versions attached then, and names that version on the reviewer's review as
 * {@code sentBackCopy}.
 *
 * <p>It follows the step writing the decision down, in the same delivery, so the review is the one the decider has
 * open for the requirement. The activity names the approval requirement in its {@code requirement} property, as that
 * step does.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class KeepCopyHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "keepCopy";

    /** The activity property naming the approval requirement whose review sent the submission back. */
    private static final String REQUIREMENT_PARAMETER = "requirement";

    /** The review property naming the copy, by the path of its version. */
    private static final String COPY_PROPERTY = "sentBackCopy";

    private static final Logger LOGGER = LoggerFactory.getLogger(KeepCopyHandler.class);

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Resource host = ExecutionHost.of(context);
        final Submission submission = SubmissionHost.read(host, HANDLER_NAME);
        final ApprovalRequirement requirement = requirementSentBack(submission, context.getActivity());
        final Review review = submission.getOpenReview(context.getActor(), requirement);
        if (review == null) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " keeps a copy for the decider's review of " + requirement.getName() + ", and they have none open");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final String hostPath = host.getPath();
        final String reviewPath = review.getPath();
        // A check-in refuses pending changes, so the copy waits for the delivery, the decision with it, to commit
        context.afterCommit(() -> keep(resolver, hostPath, reviewPath));
    }

    private static ApprovalRequirement requirementSentBack(final Submission submission, final Activity activity)
        throws WorkflowDefinitionException
    {
        final String named = activity.get(REQUIREMENT_PARAMETER, String.class);
        final ApprovalRequirement requirement =
            named == null ? null : submission.getSchemaVersion().getApprovalRequirement(named);
        if (requirement == null) {
            throw new WorkflowDefinitionException("The activity " + activity.getPath() + " keeps a copy for "
                + named + ", which is no approval this submission's schema version asks");
        }
        return requirement;
    }

    /**
     * Records the version and names it on the review. The delivery has committed, so a failure cannot undo it: it is
     * recorded, and the review simply names no copy.
     *
     * @param resolver the engine's resolver, the delivery committed
     * @param hostPath the submission
     * @param reviewPath the review that sent it back
     */
    private static void keep(final ResourceResolver resolver, final String hostPath, final String reviewPath)
    {
        try {
            final String copy = VersioningUtils.recordVersion(resolver, hostPath);
            final Resource review = resolver.getResource(reviewPath);
            if (copy != null && review != null) {
                Objects.requireNonNull(review.adaptTo(ModifiableValueMap.class), "A review can be written")
                    .put(COPY_PROPERTY, copy);
                resolver.commit();
            }
        } catch (final PersistenceException e) {
            LOGGER.error("Could not keep a copy of {} as it was sent back: {}", hostPath, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(KeepCopyHandler.class, "keepCopy").about(hostPath));
        }
    }
}
