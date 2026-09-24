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
package io.uhndata.iap.extraction.internal;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;

/**
 * Who may ask for a submission's reading to change: the person who raised it, while it is still a draft. The
 * events that do this are open to everyone at the door, the same way saving answers is.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmitterAccess
{
    private SubmitterAccess()
    {
        // Static helper
    }

    /**
     * Refuse anybody but the submitter, and anything once the request has been sent.
     *
     * @param submission the submission being read
     * @param actor the user whose action this is
     * @throws NotAuthorizedException when somebody else asks
     * @throws InvalidStateException when the request is no longer a draft
     */
    static void checkMayChange(final Submission submission, final String actor)
        throws NotAuthorizedException, InvalidStateException
    {
        if (actor == null || !actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may change how it is read");
        }
        if (!submission.isDraft()) {
            throw new InvalidStateException("This request has been submitted and can no longer be changed");
        }
    }
}
