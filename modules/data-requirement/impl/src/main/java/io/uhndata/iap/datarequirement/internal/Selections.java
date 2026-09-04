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
package io.uhndata.iap.datarequirement.internal;

import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Finding the selection that answers a requirement, which both the form projection and the save need and must
 * agree on.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Selections
{
    private Selections()
    {
        // Utility class, not to be instantiated
    }

    /**
     * The selection recorded against one requirement, if there is one.
     *
     * <p>Found by the reference it holds rather than by any name, which is what makes a selection node's name free
     * to be a UUID and what keeps saving idempotent: a second save updates the one already there instead of
     * leaving two answers to the same question.</p>
     *
     * @param submission the submission being read or written
     * @param requirement the requirement in question
     * @return the selection answering it, or {@code null} if nothing has been chosen yet
     */
    static Selection of(final Submission submission, final Requirement requirement)
    {
        // isOfType as well as the adaptation: adapting alone does not reliably filter by resource type, and a
        // submission holds answers and documents beside its selections
        return submission.getChildren(Selection.class).stream()
            .filter(candidate -> candidate.isOfType(Selection.RESOURCE_TYPE))
            .filter(candidate -> fulfills(candidate, requirement))
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether one selection was recorded in answer to one requirement.
     *
     * @param selection a selection on the submission
     * @param requirement the requirement in question
     * @return {@code true} if the selection says it fulfills that requirement
     */
    private static boolean fulfills(final Selection selection, final Requirement requirement)
    {
        final Requirement fulfilled = selection.getFulfills();
        return fulfilled != null && requirement.getPath().equals(fulfilled.getPath());
    }
}
