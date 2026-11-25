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

import java.util.Calendar;
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * PRETOTYPE. The service task that keeps a copy of a submission as a reviewer group sent it back for changes. Once
 * the delivery commits, it checks the submission in, which saves a version of it with its answers and the document
 * versions attached then, checks it out again, and names that version on the review asking for the changes, as
 * {@code sentBackCopy}, for whoever compares the resubmission with it. The activity names the approval requirement
 * in its {@code requirement} property, as the step writing the decision down does.
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

    private static final String REVIEW_NODE_TYPE = "sub:Review";

    private static final String CHANGES_REQUESTED = "changes-requested";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource host = ExecutionHost.of(context);
        final Submission submission = SubmissionHost.read(host, HANDLER_NAME);
        final String named = context.getActivity().get(REQUIREMENT_PARAMETER, String.class);
        final ApprovalRequirement requirement = submission.getSchemaVersion().getApprovalRequirements().stream()
            .filter(candidate -> candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " keeps a copy for " + named + ", which is no approval this submission's schema version asks"));
        final Resource review = sentBackBy(host, requirement);
        if (review == null) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " keeps a copy for a review sending the submission back, and no review of " + named + " did");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final String hostPath = host.getPath();
        final String reviewPath = review.getPath();
        // A check-in refuses pending changes, so the copy waits for the delivery, the decision with it, to commit
        context.afterCommit(() -> keep(resolver, hostPath, reviewPath));
    }

    /**
     * The review of the requirement that asked for changes, the latest decided if there are several.
     *
     * @param host the submission
     * @param requirement the approval requirement reviewed
     * @return the review; {@code null} if none of the requirement's reviews asks for changes
     */
    private static Resource sentBackBy(final Resource host, final ApprovalRequirement requirement)
    {
        Resource latest = null;
        Calendar latestAt = null;
        for (final Resource child : host.getChildren()) {
            final var values = child.getValueMap();
            final Taggable tags = child.adaptTo(Taggable.class);
            if (REVIEW_NODE_TYPE.equals(values.get("jcr:primaryType", String.class))
                && requirement.getIdentifier().equals(values.get("requirement", String.class))
                && tags != null && tags.hasTag(CHANGES_REQUESTED)) {
                final Calendar at = values.get("decidedAt", Calendar.class);
                if (latest == null || at != null && (latestAt == null || at.after(latestAt))) {
                    latest = child;
                    latestAt = at;
                }
            }
        }
        return latest;
    }

    private static void keep(final ResourceResolver resolver, final String hostPath, final String reviewPath)
    {
        try {
            final VersionManager versions = Objects.requireNonNull(resolver.adaptTo(Session.class),
                "The engine's resolver is backed by a JCR session").getWorkspace().getVersionManager();
            final Version copy = versions.checkin(hostPath);
            versions.checkout(hostPath);
            final Resource review = resolver.getResource(reviewPath);
            if (review != null) {
                Objects.requireNonNull(review.adaptTo(ModifiableValueMap.class), "A review can be written")
                    .put(COPY_PROPERTY, copy.getPath());
                resolver.commit();
            }
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException("Could not keep a copy of " + hostPath + " as it was sent back", e);
        }
    }
}
