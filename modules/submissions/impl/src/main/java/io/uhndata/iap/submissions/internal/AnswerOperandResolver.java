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

import java.util.Optional;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * The answer itself is any node whose {@code question} property references the identified question, looked up
 * nearest-scope-first: the context's own subtree is searched before widening, one ancestor at a time, up to and
 * including the submission that holds it. When the same question is answered several times in repeated blocks, a
 * condition evaluated inside one block therefore sees that block's own answer, not an arbitrary one.
 * </p>
 *
 * <p><strong>The submission is the boundary, and it is deliberately the submission rather than any
 * {@code data:Entity}.</strong> This resolver used to live in the conditions module, which cannot name a
 * submission — it depends on content and entities only, while schemas and submissions depend on it — so it
 * stopped at the generic entity as a stand-in. That stand-in was wrong in a way that mattered: a workflow
 * instance is an {@code data:Entity} too, and it holds no answers, so a gateway guard asking about an answer was
 * stopped at the instance and never reached the request it was guarding. "Requests over thirty days need a second
 * approval" could not be written.</p>
 *
 * <p>Stopping at the submission fixes that and keeps what the old rule was protecting. Widening cannot leak
 * across records, because a sibling submission is never an <em>ancestor</em>: from anywhere inside one, the walk
 * meets its own submission and stops. And an {@code answer} is a submissions word, so this resolver belongs in
 * the module that owns it — which is what {@code OperandResolver} being a published SPI is for.</p>
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

    private static final Pattern UUID_FORMAT = Pattern
        .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

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
        return "answer";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            LOGGER.warn("Answer operand at {} does not identify a question", operand.getPath());
            return Operand.EMPTY;
        }
        final Question question = this.resolveQuestion(value[0], operand, context);
        if (question == null) {
            return Operand.EMPTY;
        }
        Content scope = context;
        Content searched = null;
        while (scope != null) {
            final Optional<Content> answer = this.findAnswer(scope, searched, question.identifier);
            if (answer.isPresent()) {
                return Operand.of(answer.get().get(VALUE_PROPERTY), question.type);
            }
            if (scope.isOfType(Submission.RESOURCE_TYPE)) {
                break;
            }
            searched = scope;
            scope = scope.getParent();
        }
        return Operand.of(null, question.type);
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
