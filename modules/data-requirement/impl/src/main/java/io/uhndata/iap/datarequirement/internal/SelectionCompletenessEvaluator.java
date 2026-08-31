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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;

/**
 * Judges the selections of a submission: a data requirement that applies is met once at least one field has been
 * chosen for it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class SelectionCompletenessEvaluator implements CompletenessEvaluator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SelectionCompletenessEvaluator.class);

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public List<Resource> evaluate(final Resource submission)
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        final List<Resource> incomplete = new ArrayList<>();
        for (final DataRequirement requirement : Selections.getDataRequirements(model, this.conditions)) {
            final Selection selection = Selections.findSelection(model, requirement);
            if (selection == null) {
                LOGGER.warn("{} has no selection for {}, it should have been automatically created",
                    submission.getPath(), requirement.getPath());
                // A problem rather than a failure: a part creator left an item without its part
                ErrorLogger.logProblem("A submission has no selection for a data requirement",
                    ErrorContext.of(SelectionCompletenessEvaluator.class, "evaluate")
                        .about(submission.getPath())
                        .with("requirement", requirement.getPath()));
                incomplete.add(submission);
            } else if (selection.getFieldKeys().isEmpty()) {
                incomplete.add(Objects.requireNonNull(submission.getResourceResolver().getResource(selection.getPath()),
                    "A listed selection exists"));
            }
        }
        return incomplete;
    }
}
