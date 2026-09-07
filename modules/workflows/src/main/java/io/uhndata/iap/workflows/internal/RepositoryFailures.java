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
package io.uhndata.iap.workflows.internal;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.nodetype.ConstraintViolationException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Translates failed repository operations into the acceptance layer they belong to: a lost race is the state
 * layer, a node type constraint violation is the payload layer, and anything else is the machinery failing. This
 * is what lets the repository do the validating, with the engine merely putting the right name on the refusal.
 *
 * <p>Note what is <em>not</em> here: an access denial is not the user being refused. The engine runs privileged,
 * having already decided from the definition that the actor was allowed, so if the repository turns it away the
 * engine's own service user is short of rights — a deployment fault, and nothing the caller can do anything
 * about.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RepositoryFailures
{
    private RepositoryFailures()
    {
    }

    /**
     * Puts the right name on a failed repository operation.
     *
     * @param failure the repository failure
     * @return the matching typed exception, ready to be thrown
     */
    static WorkflowException translate(final PersistenceException failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidItemStateException) {
                // Somebody else changed the same thing first. That is the state layer, not a fault: what the
                // caller asked for was reasonable when they asked, and is not any more
                return new NoApplicableWorkflowException("Somebody else changed this at the same time; look at"
                    + " where it has got to and try again");
            }
            if (cause instanceof AccessDeniedException) {
                return new WorkflowFailedException("The workflow engine is not allowed to do what the workflow"
                    + " asked of it; its service user is missing rights", failure);
            }
            if (cause instanceof ConstraintViolationException) {
                return new InvalidPayloadException("The submitted data is not acceptable: " + cause.getMessage(),
                    failure);
            }
        }
        return new WorkflowFailedException("The workflow could not be executed: " + failure.getMessage(), failure);
    }

    /**
     * Puts the right name on the engine's own session being unavailable, which is a deployment fault: the
     * service user mapping is missing or the bundle asking is not the one mapped.
     *
     * @param failure the refused login
     * @return the matching typed exception, ready to be thrown
     */
    static WorkflowException translate(final LoginException failure)
    {
        return new WorkflowFailedException("The workflow engine's service user is not available", failure);
    }
}
