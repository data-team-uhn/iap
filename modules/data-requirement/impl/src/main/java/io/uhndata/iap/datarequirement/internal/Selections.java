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

import java.util.List;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Which data requirements a submission is asked now, and which selection answers each.
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
     * The data requirements this submission is asked now, in schema order.
     *
     * @param submission the submission whose answers decide what applies
     * @param conditions decides whether each requirement applies
     * @return the data requirements that apply
     */
    static List<DataRequirement> getDataRequirements(final Submission submission,
        final ConditionEvaluator conditions)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DataRequirement.class::isInstance)
            .filter(requirement -> conditions.applies(requirement, submission))
            .map(DataRequirement.class::cast)
            .toList();
    }

    /**
     * The selection recorded against one requirement, if there is one.
     *
     * <p>Found by the reference it holds rather than by name, so a selection's node name is free to be a UUID. A
     * second save finds and updates the selection the first one made.</p>
     *
     * @param submission the submission being read or written
     * @param requirement the requirement in question
     * @return the selection answering it, or {@code null} if the submission holds none
     */
    static Selection findSelection(final Submission submission, final Requirement requirement)
    {
        // Adaptation does not filter by resource type, and a submission holds answers and documents beside its
        // selections
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
