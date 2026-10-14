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

import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
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
 * The service task that records whether a submission still lacks something its author has to supply, as the
 * {@code incomplete} tag.
 *
 * <p>It acts on the submission an earlier step of the run created, if there is one, and otherwise on the event's
 * target. So the create workflow marks a new submission from the start, and the save and attach workflows mark the
 * submission they changed.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class MarkCompletenessHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "markCompleteness";

    /** The tag saying that something the submission is asked for has not been supplied. */
    public static final String INCOMPLETE_TAG = "incomplete";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = ExecutionHost.of(context);
        if (!target.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The markCompleteness task only applies to submissions, not to "
                + target.getResourceType());
        }
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        final Taggable taggable = Objects.requireNonNull(target.adaptTo(Taggable.class),
            "Any resource can be read as taggable content");
        // An approval is given by somebody else, later, so counting it would keep every draft incomplete
        final boolean incomplete = submission.getMissingRequirements().stream()
            .anyMatch(requirement -> !(requirement instanceof ApprovalRequirement));
        if (incomplete) {
            taggable.tag(INCOMPLETE_TAG, true);
        } else {
            taggable.untag(INCOMPLETE_TAG, true);
        }
    }
}
