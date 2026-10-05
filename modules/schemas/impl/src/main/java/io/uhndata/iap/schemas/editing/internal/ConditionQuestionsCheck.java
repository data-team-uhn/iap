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
package io.uhndata.iap.schemas.editing.internal;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.spi.SchemaValidityCheck;

/**
 * Check that every condition on an answer names a question of the same schema version.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SchemaValidityCheck.class)
public class ConditionQuestionsCheck implements SchemaValidityCheck
{
    private static final Pattern UUID_FORMAT =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, "cond:ConditionOperand")
            .filter(operand -> "answer".equals(operand.getValueMap().get("source", "literal")))
            .filter(operand -> !namesAQuestionOf(operand.getValueMap().get("value", new String[0]), version))
            .map(operand -> DraftParts.describe(DraftParts.conditioned(operand), version)
                + " has a condition on a question that is not in this version")
            .toList();
    }

    private static boolean namesAQuestionOf(final String[] reference, final Resource version)
    {
        return reference.length > 0 && isQuestionOf(reference[0], version);
    }

    private static boolean isQuestionOf(final String reference, final Resource version)
    {
        if (!UUID_FORMAT.matcher(reference).matches()) {
            final Resource question = version.getChild(reference);
            return question != null
                && DraftParts.QUESTION.equals(question.getValueMap().get(DraftParts.PRIMARY_TYPE, ""));
        }
        final Session session = Objects.requireNonNull(version.getResourceResolver().adaptTo(Session.class),
            "Schemas are stored in a JCR repository");
        try {
            final Node question = session.getNodeByIdentifier(reference);
            return question.getPath().startsWith(version.getPath() + "/") && question.isNodeType(DraftParts.QUESTION);
        } catch (final RepositoryException e) {
            // Not found, which is exactly the problem being looked for
            return false;
        }
    }
}
