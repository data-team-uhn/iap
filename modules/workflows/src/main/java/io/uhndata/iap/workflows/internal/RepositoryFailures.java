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

import org.apache.sling.api.resource.PersistenceException;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Translates failed repository operations into the acceptance layer they belong to: a lost race is the state
 * layer, a node type constraint violation is the payload layer, and anything else is the machinery failing. This
 * is what lets the repository do the validating, with the engine merely putting the right name on the refusal.
 *
 * <p>Note what is <em>not</em> here: an access denial is not the user being refused. The engine runs privileged,
 * having already decided from the definition that the actor was allowed, so if the repository turns it away the
 * engine's own service user is short of rights, which is a deployment fault and nothing the caller can do
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
     * Runs something that commits, and runs it again when it lost a race with another commit. Each attempt must
     * start from a fresh view of the repository, so that the retry sees what the other commit did.
     *
     * @param <T> what the attempt returns
     * @param retries how many times to run it again after a lost race
     * @param attempt what to run
     * @return what the successful attempt returned
     * @throws WorkflowException what the last attempt threw, or the first failure that was not a lost race
     */
    static <T> T retryingConflicts(final int retries, final Attempt<T> attempt) throws WorkflowException
    {
        int left = retries;
        while (true) {
            try {
                return attempt.run();
            } catch (final InvalidStateException e) {
                if (left-- <= 0 || !isConflict(e)) {
                    throw e;
                }
            }
        }
    }

    /**
     * Whether a failure was a lost race: another session committed a change to something this one changed too.
     *
     * @param failure the failure
     * @return {@code true} if the repository refused the commit over a conflicting change
     */
    static boolean isConflict(final Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidItemStateException) {
                return true;
            }
        }
        return false;
    }

    /**
     * One attempt at something that commits.
     *
     * @param <T> what it returns
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Attempt<T>
    {
        /**
         * Runs the attempt.
         *
         * @return its result
         * @throws WorkflowException when it fails
         */
        T run() throws WorkflowException;
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
                return new InvalidStateException("Somebody else changed this at the same time; look at where it"
                    + " has got to and try again", failure);
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
}
