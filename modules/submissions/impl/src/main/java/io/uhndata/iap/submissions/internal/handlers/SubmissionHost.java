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

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;

/**
 * The check every submission task makes first: that the workflow pointed it at a submission.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionHost
{
    private SubmissionHost()
    {
    }

    /**
     * Returns the task's host, once it is known to be a submission.
     *
     * @param host the resource the task acts on
     * @param task the handler name, for the message
     * @return the same host
     * @throws WorkflowDefinitionException when the workflow points the task at anything else
     */
    static Resource require(final Resource host, final String task) throws WorkflowDefinitionException
    {
        if (!host.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The " + task + " task only applies to submissions, not to "
                + host.getResourceType());
        }
        return host;
    }

    /**
     * Reads the task's host as a submission.
     *
     * @param host the resource the task acts on
     * @param task the handler name, for the message
     * @return the submission
     * @throws WorkflowDefinitionException when the workflow points the task at anything else
     */
    static Submission read(final Resource host, final String task) throws WorkflowDefinitionException
    {
        return Objects.requireNonNull(require(host, task).adaptTo(Submission.class),
            "A sub:Submission resource always adapts to its model");
    }
}
