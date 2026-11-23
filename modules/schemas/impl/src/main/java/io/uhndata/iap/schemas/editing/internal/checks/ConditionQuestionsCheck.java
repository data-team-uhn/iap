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
package io.uhndata.iap.schemas.editing.internal.checks;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.AnswerDependencies;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Checks that every condition on an answer or a decision names a question of the same schema version, by
 * {@code jcr:uuid} or by its path within the version, and that every condition on a validation names a validation
 * of the version by its path. Otherwise the condition is never true.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class ConditionQuestionsCheck extends SchemaVersionCheck
{
    private static final String VALIDATION_TYPE = "sch:ValidationRequirement";

    private static final Pattern UUID_FORMAT =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, "cond:ConditionOperand")
            .map(operand -> findProblem(operand, version))
            .filter(Objects::nonNull)
            .toList();
    }

    private static String findProblem(final Resource operand, final Resource version)
    {
        final ValueMap properties = operand.getValueMap();
        final String[] reference = properties.get("value", new String[0]);
        final String missing = switch (properties.get("source", ConditionOperand.DEFAULT_SOURCE)) {
            case AnswerDependencies.ANSWER_SOURCE, ConditionOperand.DECISION_SOURCE ->
                reference.length > 0 && isQuestionOf(reference[0], version) ? null : "a question";
            case ConditionOperand.VALIDATION_SOURCE ->
                reference.length > 0 && isValidationOf(reference[0], version) ? null : "a validation";
            default -> null;
        };
        return missing == null ? null
            : DraftParts.describe(DraftParts.conditioned(operand), version) + " has a condition on " + missing
                + " that is not in this version";
    }

    private static boolean isValidationOf(final String path, final Resource version)
    {
        final Resource validation = version.getChild(path);
        return validation != null && VALIDATION_TYPE.equals(validation.getValueMap().get(DraftParts.PRIMARY_TYPE, ""));
    }

    private static boolean isQuestionOf(final String reference, final Resource version)
    {
        if (!UUID_FORMAT.matcher(reference).matches()) {
            final Resource question = version.getChild(reference);
            return question != null
                && DraftParts.QUESTION_TYPE.equals(question.getValueMap().get(DraftParts.PRIMARY_TYPE, ""));
        }
        final Session session = Objects.requireNonNull(version.getResourceResolver().adaptTo(Session.class),
            "Schemas are stored in a JCR repository");
        try {
            final Node question = session.getNodeByIdentifier(reference);
            return question.getPath().startsWith(version.getPath() + "/")
                && question.isNodeType(DraftParts.QUESTION_TYPE);
        } catch (final RepositoryException e) {
            // Not found: the problem this check reports
            return false;
        }
    }
}
