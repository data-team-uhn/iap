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
package io.uhndata.iap.conditions.spi;

import java.util.List;
import java.util.Locale;

import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;

/**
 * Computes the actual values of one side of a comparison at evaluation time. Each resolver implements one operand
 * source (e.g. {@code literal}, {@code answer}, {@code tags}, {@code property}) and is picked by matching its
 * {@link #getSource() name} against the {@code source} property of the {@code cond:ConditionOperand} being
 * resolved; registering a new resolver service is all it takes to support a new kind of operand. It describes
 * itself for the {@code /ConditionTypes} catalogue, which the condition builder reads.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface OperandResolver extends DocumentedItem
{
    /** The catalogue category sources are listed under. */
    String CATEGORY = "Sources";

    /**
     * The operand source this resolver implements, matched against the {@code source} property of operand nodes.
     *
     * @return a source name, e.g. {@code answer}
     */
    @NotNull
    String getSource();

    @Override
    @NotNull
    default String getName()
    {
        return getSource();
    }

    @Override
    @Nullable
    default String getDescription()
    {
        return null;
    }

    /**
     * What the operand's value names, when it names something to read, e.g. {@code Question}.
     *
     * @return a label, or {@code null} for a source that reads the same thing whatever its value
     */
    @Nullable
    default String getValueLabel()
    {
        return null;
    }

    /**
     * How an operand of this source reads in a sentence, with {@code {label}} standing for what its value names,
     * e.g. {@code the answer to “{label}”}.
     *
     * @return a phrase template
     */
    @NotNull
    default String getPhrase()
    {
        return "{label}";
    }

    /**
     * The type of the values an operand of this source holds, for a source whose values do not depend on what it
     * names.
     *
     * @return a type, or {@code null} when the source cannot tell
     */
    @Nullable
    default OperandType getValueType()
    {
        return null;
    }

    /**
     * Whether an operand of this source holds several values, for a source whose values do not depend on what it
     * names.
     *
     * @return whether it does, or {@code null} when the source cannot tell
     */
    @Nullable
    default Boolean isMultiple()
    {
        return null;
    }

    /**
     * Where the values an operand of this source is compared with can be picked from: the path of an autodoc
     * catalogue, whose items are the choices, e.g. {@code /Tags.doc.json}.
     *
     * @return a path, or {@code null} when the values are typed in
     */
    @Nullable
    default String getChoices()
    {
        return null;
    }

    /**
     * Whether the condition builder offers this source as something to compare. A source that is not offered is
     * still evaluated and shown.
     *
     * @return {@code true} unless the source is meant only for conditions written by hand
     */
    default boolean isOffered()
    {
        return true;
    }

    /**
     * Where the source comes in the list the builder offers, the lowest first.
     *
     * @return a position
     */
    default int getOrder()
    {
        return 0;
    }

    @Override
    @NotNull
    default List<String> getDocumentationCategories()
    {
        return List.of(CATEGORY);
    }

    @Override
    @NotNull
    default JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("phrase", getPhrase())
            .add("offered", isOffered())
            .add("order", getOrder());
        final String valueLabel = getValueLabel();
        if (valueLabel != null) {
            json.add("valueLabel", valueLabel);
        }
        final OperandType type = getValueType();
        if (type != null) {
            json.add("type", type.name().toLowerCase(Locale.ROOT));
        }
        final Boolean multiple = isMultiple();
        if (multiple != null) {
            json.add("multiple", multiple);
        }
        final String choices = getChoices();
        if (choices != null) {
            json.add("choices", choices);
        }
        return json;
    }

    /**
     * Compute the actual values of an operand, in their natural stored types — coercion for comparison is the
     * evaluator's job, after it unifies the types of the two sides. A resolver that knows the authoritative type
     * of its values (e.g. the referenced question's declared data type) states it on the returned operand via
     * {@link Operand#of(Object, OperandType)}, steering that unification.
     *
     * @param operand the operand definition being resolved
     * @param context the content the condition is evaluated against
     * @return the resolved values, never {@code null}; an empty operand when nothing matches the definition, so
     *         that the {@code is empty} operator can test for absence
     */
    @NotNull
    Operand resolve(@NotNull ConditionOperand operand, @NotNull Content context);

    /**
     * The entity enclosing a piece of content, e.g. the submission an answer belongs to. This is the record a
     * condition is usually asked about, so resolvers commonly interpret their operand definition relative to it
     * rather than to the exact context, which may be anywhere inside the entity.
     *
     * @param content a piece of content, may be {@code null}
     * @return the closest ancestor-or-self that is an {@code data:Entity}, or {@code null} if there is none
     */
    @Nullable
    static Content findEnclosingEntity(@Nullable final Content content)
    {
        Content current = content;
        while (current != null && !current.isOfType(Entity.RESOURCE_TYPE)) {
            current = current.getParent();
        }
        return current;
    }
}
