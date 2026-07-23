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
package io.uhndata.iap.conditions.internal;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.entities.models.Entity;

/**
 * Resolves {@code answer} operands: the recorded answer to a question, e.g. a submission's answer to one of its
 * schema's questions. The operand value identifies the question, either by its UUID or by its path relative to the
 * entity holding the operand definition (e.g. the schema version), so questions with the same name in different
 * containers never collide. The question's own declared {@code dataType} is reported as the resolved operand's
 * type, steering the evaluator's comparison type unification.
 *
 * <p>
 * The answer itself is any node whose {@code question} property references the identified question, looked up
 * nearest-scope-first: the context resource's own subtree is searched before widening, one ancestor at a time, to
 * the whole enclosing entity. When the same question is answered several times in repeated blocks, a condition
 * evaluated inside one block therefore sees that block's own answer, not an arbitrary one.
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
    public Operand resolve(final ConditionOperand operand, final Resource context)
    {
        if (operand.getValue() == null || operand.getValue().length == 0) {
            LOGGER.warn("Answer operand at {} does not identify a question", operand.getPath());
            return Operand.EMPTY;
        }
        final Question question = this.resolveQuestion(operand.getValue()[0], operand, context);
        if (question == null) {
            return Operand.EMPTY;
        }
        Resource scope = context;
        Resource searched = null;
        while (scope != null) {
            final Optional<Resource> answer = this.findAnswer(scope, searched, question.identifier);
            if (answer.isPresent()) {
                return Operand.of(answer.get().getValueMap().get(VALUE_PROPERTY), question.type);
            }
            if (scope.isResourceType(Entity.RESOURCE_TYPE)) {
                break;
            }
            searched = scope;
            scope = scope.getParent();
        }
        return Operand.of(null, question.type);
    }

    private Question resolveQuestion(final String reference, final ConditionOperand operand,
        final Resource context)
    {
        if (UUID_FORMAT.matcher(reference).matches()) {
            // The identifier is usable as-is; the question node is only needed for its declared type,
            // so failing to load it just leaves the type to be inferred from the answer values.
            return new Question(reference,
                declaredType(findByIdentifier(context.getResourceResolver(), reference)));
        }
        final Resource base = OperandResolver
            .findEnclosingEntity(context.getResourceResolver().getResource(operand.getPath()));
        final Resource question = base == null ? null : base.getChild(reference);
        if (question == null) {
            LOGGER.warn("Answer operand at {} references unresolvable question {}", operand.getPath(), reference);
            return null;
        }
        final String questionId = question.getValueMap().get("jcr:uuid", String.class);
        if (questionId == null) {
            LOGGER.warn("Answer operand at {} references non-referenceable question {}", operand.getPath(),
                question.getPath());
            return null;
        }
        return new Question(questionId, declaredType(question));
    }

    private static Resource findByIdentifier(final ResourceResolver resolver, final String identifier)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return null;
        }
        try {
            return resolver.getResource(session.getNodeByIdentifier(identifier).getPath());
        } catch (final RepositoryException ex) {
            return null;
        }
    }

    private static OperandType declaredType(final Resource question)
    {
        final String dataType =
            question == null ? null : question.getValueMap().get(DATA_TYPE_PROPERTY, String.class);
        return dataType == null ? null : OperandType.parse(dataType);
    }

    private Optional<Resource> findAnswer(final Resource node, final Resource searched, final String questionId)
    {
        if (questionId.equals(node.getValueMap().get(QUESTION_PROPERTY, String.class))) {
            return Optional.of(node);
        }
        return StreamSupport.stream(node.getChildren().spliterator(), false)
            .filter(child -> searched == null || !child.getPath().equals(searched.getPath()))
            .map(child -> this.findAnswer(child, null, questionId))
            .flatMap(Optional::stream)
            .findFirst();
    }
}
