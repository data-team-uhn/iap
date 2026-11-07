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
import java.util.Optional;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.AnswerDependencies;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Resolves {@code answer} operands: a submission's recorded answer to one of its schema's questions. The operand's
 * value names the question, by its {@code jcr:uuid} or by its path within the schema version the submission answers.
 * A path tells apart questions of the same name in different sections, and means the same wherever the condition is
 * defined: in that schema version, or in a workflow running over the submission. The question's {@code dataType} is
 * the type of the resolved operand, which decides how the evaluator compares it.
 *
 * <p>
 * The answer is any node whose {@code question} property references that question. It is looked up nearest scope
 * first: the context's own subtree, then one ancestor at a time up to the submission holding the context. A
 * condition evaluated inside a node that holds its own answer to the question sees that answer, before any other in
 * the submission. A context outside any submission has no answer.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class AnswerOperandResolver implements OperandResolver
{
    /** The property through which an answer node references its question. */
    private static final String QUESTION_PROPERTY = "question";

    /** The property holding an answer node's values. */
    private static final String VALUE_PROPERTY = "value";

    /** The property holding a question node's data type. */
    private static final String DATA_TYPE_PROPERTY = "dataType";

    /**
     * A {@code jcr:uuid} as Oak writes it, in lower case. {@link AnswerDependencies} finds what depends on a question
     * by that exact spelling, so no other spelling names a question.
     */
    private static final Pattern UUID_FORMAT =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerOperandResolver.class);

    /**
     * The {@code jcr:uuid} and the data type of the question an operand names.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Question
    {
        private final String identifier;

        private final OperandType type;

        Question(final String identifier, final OperandType type)
        {
            this.identifier = identifier;
            this.type = type;
        }
    }

    @Override
    public String getSource()
    {
        return AnswerDependencies.ANSWER_SOURCE;
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            LOGGER.warn("Answer operand at {} does not identify a question", operand.getPath());
            return Operand.NO_VALUES;
        }
        final List<Content> scopes = scopesUpToSubmission(context);
        if (scopes.isEmpty()) {
            return Operand.NO_VALUES;
        }
        final Question question = this.resolveQuestion(value[0], operand, scopes.getLast());
        if (question == null) {
            return Operand.NO_VALUES;
        }
        Content searched = null;
        for (final Content scope : scopes) {
            final Optional<Content> answer = this.findAnswer(scope, searched, question.identifier);
            if (answer.isPresent()) {
                return Operand.of(this.readValues(answer.get()), question.type);
            }
            searched = scope;
        }
        return Operand.of(null, question.type);
    }

    /**
     * Returns the values of the answer found for the operand. A subclass can read something else, such as a
     * suggestion.
     *
     * @param answer the answer node
     * @return its recorded value(s), or {@code null} when it holds none
     */
    protected Object readValues(final Content answer)
    {
        return answer.get(VALUE_PROPERTY);
    }

    private static List<Content> scopesUpToSubmission(final Content context)
    {
        final List<Content> scopes = new ArrayList<>();
        for (Content scope = context; scope != null; scope = scope.getParent()) {
            scopes.add(scope);
            if (scope.isOfType(Submission.RESOURCE_TYPE)) {
                return scopes;
            }
        }
        return List.of();
    }

    private Question resolveQuestion(final String reference, final ConditionOperand operand, final Content submission)
    {
        if (UUID_FORMAT.matcher(reference).matches()) {
            // The question node is read only for its type. If it cannot be read, the type is inferred from the values.
            return new Question(reference, declaredType(submission.getReference(reference, Content.class)));
        }
        final SchemaVersion version = schemaVersionOf(submission);
        final Content question = version == null ? null : version.getChild(reference, Content.class);
        if (question == null) {
            LOGGER.warn("Answer operand at {} names question {}, which the schema version of {} lacks",
                operand.getPath(), reference, submission.getPath());
            ErrorLogger.logProblem("An answer operand names a question the submission's schema version does not have",
                problemIn(operand).with("question", reference).with("submission", submission.getPath()));
            return null;
        }
        final String questionId = question.get("jcr:uuid", String.class);
        if (questionId == null) {
            LOGGER.warn("Answer operand at {} references non-referenceable question {}", operand.getPath(),
                question.getPath());
            ErrorLogger.logProblem("An answer operand names something that is not a question",
                problemIn(operand).with("question", question.getPath()));
            return null;
        }
        return new Question(questionId, declaredType(question));
    }

    private static ErrorContext problemIn(final ConditionOperand operand)
    {
        return ErrorContext.of(AnswerOperandResolver.class, "resolve").about(operand.getPath());
    }

    private static SchemaVersion schemaVersionOf(final Content submission)
    {
        return Optional.ofNullable(submission.as(Submission.class)).map(Submission::findSchemaVersion).orElse(null);
    }

    private static OperandType declaredType(final Content question)
    {
        final String dataType = question == null ? null : question.get(DATA_TYPE_PROPERTY, String.class);
        return dataType == null ? null : OperandType.parse(dataType);
    }

    private Optional<Content> findAnswer(final Content node, final Content searched, final String questionId)
    {
        if (questionId.equals(node.get(QUESTION_PROPERTY, String.class))) {
            return Optional.of(node);
        }
        return node.getChildren(Content.class).stream()
            .filter(child -> searched == null || !child.getPath().equals(searched.getPath()))
            .map(child -> this.findAnswer(child, null, questionId))
            .flatMap(Optional::stream)
            .findFirst();
    }
}
