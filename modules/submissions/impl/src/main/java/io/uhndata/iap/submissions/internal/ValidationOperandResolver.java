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

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;

/**
 * Resolves {@code validation} operands: what a model decided about an upload, as {@code yes}, {@code no} or
 * {@code unsure} (see {@link Validation#getOutcome()}). The operand value names the validation requirement by its
 * path within the schema version the submission answers, such as {@code is_proposal}, so it keeps working when the
 * schema is copied to a new version. Before the model is asked, the operand is empty.
 *
 * <p>A workflow gateway reads this to decide whether to go on with an upload, before any person has looked at it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = OperandResolver.class)
public class ValidationOperandResolver implements OperandResolver
{
    /** The source of an operand reading a validation's outcome. */
    public static final String VALIDATION_SOURCE = "validation";

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationOperandResolver.class);

    @Override
    public String getSource()
    {
        return VALIDATION_SOURCE;
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        final Submission submission = findSubmission(context);
        if (value == null || value.length == 0 || submission == null) {
            return Operand.NO_VALUES;
        }
        final ValidationRequirement requirement = findRequirement(value[0], submission);
        if (requirement == null) {
            LOGGER.warn("Validation operand at {} names {}, which is no validation requirement in the schema of {}",
                operand.getPath(), value[0], submission.getPath());
            ErrorLogger.logProblem("A validation operand names no validation requirement of the submission's schema",
                ErrorContext.of(ValidationOperandResolver.class, "resolve").about(operand.getPath())
                    .with("requirement", value[0]).with("submission", submission.getPath()));
            return Operand.NO_VALUES;
        }
        final Validation validation = submission.findValidation(requirement);
        final String outcome = validation == null ? null : validation.getOutcome();
        return outcome == null ? Operand.NO_VALUES : Operand.of(outcome, OperandType.TEXT);
    }

    private static Submission findSubmission(final Content context)
    {
        Content scope = context;
        while (scope != null && !scope.isOfType(Submission.RESOURCE_TYPE)) {
            scope = scope.getParent();
        }
        return scope == null ? null : scope.as(Submission.class);
    }

    private static ValidationRequirement findRequirement(final String path, final Submission submission)
    {
        final SchemaVersion version = submission.findSchemaVersion();
        return version == null ? null
            : version.getChild(path, ValidationRequirement.RESOURCE_TYPE, ValidationRequirement.class);
    }
}
