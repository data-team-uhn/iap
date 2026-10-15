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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Which items of its schema a submission is asked now, by the conditions they carry. The part creators and the
 * completeness evaluators read the same answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ApplicableItems
{
    private ApplicableItems()
    {
    }

    /**
     * The questions the submitter is shown, in schema order. A question is shown when it, every section around it and
     * its form requirement all apply.
     *
     * @param submission the submission whose conditions decide
     * @param conditions the condition evaluator
     * @return the questions that apply
     */
    public static List<Question> getQuestions(final Submission submission, final ConditionEvaluator conditions)
    {
        final List<Question> applicable = new ArrayList<>();
        submission.getSchemaVersion().getRequirements().stream()
            .filter(FormRequirement.class::isInstance)
            .filter(requirement -> conditions.applies(requirement, submission))
            .forEach(form -> ((FormRequirement) form).getChildren()
                .forEach(item -> collect(item, submission, conditions, applicable)));
        return applicable;
    }

    /**
     * The document requirements the submitter is asked to fulfill, in schema order.
     *
     * @param submission the submission whose conditions decide
     * @param conditions the condition evaluator
     * @return the document requirements that apply
     */
    public static List<DocumentRequirement> getDocumentRequirements(final Submission submission,
        final ConditionEvaluator conditions)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(requirement -> conditions.applies(requirement, submission))
            .collect(Collectors.toList());
    }

    private static void collect(final FormItem item, final Submission submission, final ConditionEvaluator conditions,
        final List<Question> applicable)
    {
        if (!conditions.applies(item, submission)) {
            return;
        }
        if (item instanceof Question) {
            applicable.add((Question) item);
        } else if (item instanceof Section) {
            ((Section) item).getChildren().forEach(child -> collect(child, submission, conditions, applicable));
        }
    }
}
