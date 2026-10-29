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

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.AnswerDependencies;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Resolves {@code answer} operands: the recorded answer to a question, e.g. a submission's answer to one of its
 * schema's questions. The operand value identifies the question, either by its UUID or by its path relative to the
 * entity holding the operand definition (e.g. the schema version), so questions with the same name in different
 * containers never collide. The question's own declared {@code dataType} is reported as the resolved operand's
 * type, steering the evaluator's comparison type unification.
 *
 * <p>
 * The answer itself is any node whose {@code question} property references the identified question. It is looked
 * up nearest-scope-first: the context's own subtree, then one ancestor at a time up to the submission holding the
 * context. When the same question is answered several times in repeated blocks, a condition evaluated inside one
 * block therefore sees that block's own answer, not an arbitrary one.
 * </p>
 *
 * <p>
 * The submission is the boundary because answers belong to one. A sibling submission is never an ancestor, so the
 * search cannot read another submission's answers. A context outside any submission has no answers at all.
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

    /** The property holding an answer node's recorded value(s). */
    private static final String VALUE_PROPERTY = "value";

    /** The property holding a question node's declared data type. */
    private static final String DATA_TYPE_PROPERTY = "dataType";

    /**
     * An identifier as the repository writes it, in lower case. {@link AnswerDependencies} finds what depends on a
     * question by that exact spelling, so no other spelling names a question.
     */
    private static final Pattern UUID_FORMAT =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerOperandResolver.class);

    /**
     * The identity and declared type of the question an operand references.
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
        final Question question = this.resolveQuestion(value[0], operand, context);
        if (question == null) {
            return Operand.NO_VALUES;
        }
        final Optional<Content> answer = enclosingSubmission(context)
            .flatMap(submission -> this.findNearestAnswer(context, submission, question.identifier));
        return Operand.of(answer.map(found -> found.get(VALUE_PROPERTY)).orElse(null), question.type);
    }

    private Optional<Content> findNearestAnswer(final Content context, final Content submission,
        final String questionId)
    {
        Content scope = context;
        Content searched = null;
        while (true) {
            final Optional<Content> answer = this.findAnswer(scope, searched, questionId);
            if (answer.isPresent() || scope.getPath().equals(submission.getPath())) {
                return answer;
            }
            searched = scope;
            scope = Objects.requireNonNull(scope.getParent(), "The submission is an ancestor of the context");
        }
    }

    private static Optional<Content> enclosingSubmission(final Content context)
    {
        return Stream.iterate(context, Objects::nonNull, Content::getParent)
            .filter(scope -> scope.isOfType(Submission.RESOURCE_TYPE))
            .findFirst();
    }

    private Question resolveQuestion(final String reference, final ConditionOperand operand, final Content context)
    {
        if (UUID_FORMAT.matcher(reference).matches()) {
            // The identifier is usable as-is; the question node is only needed for its declared type,
            // so failing to load it just leaves the type to be inferred from the answer values.
            return new Question(reference, declaredType(context.getReference(reference, Content.class)));
        }
        // A relative reference is resolved against the entity the operand itself belongs to, e.g. the schema
        // version, not against the context the condition is evaluated for
        final Content base = OperandResolver.findEnclosingEntity(operand);
        final Content question = base == null ? null : base.getChild(reference, Content.class);
        if (question == null) {
            LOGGER.warn("Answer operand at {} references unresolvable question {}", operand.getPath(), reference);
            return null;
        }
        final String questionId = question.get("jcr:uuid", String.class);
        if (questionId == null) {
            LOGGER.warn("Answer operand at {} references non-referenceable question {}", operand.getPath(),
                question.getPath());
            return null;
        }
        return new Question(questionId, declaredType(question));
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
