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
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;

/**
 * A way to fold an operand's values into one before it is compared, named by the {@code aggregate} property of a
 * {@code cond:ConditionOperand}, e.g. counting the options picked. A module adds one by registering an
 * implementation as a service; it is then evaluated, accepted by the checks, listed in the {@code /ConditionTypes}
 * catalogue, and offered by the condition builder.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionAggregator extends DocumentedItem
{
    /** The catalogue category aggregators are listed under. */
    String CATEGORY = "Aggregators";

    /**
     * The type the result holds, when it differs from the type of the values folded, e.g. a count is a number
     * whatever is counted. It then takes part in choosing the comparison type in place of the values' own type.
     *
     * @return a type, or {@code null} if the result has the type of the values folded
     */
    @Nullable
    OperandType getOutputType();

    /**
     * Fold the values of an operand into one.
     *
     * @param input the resolved operand, with values still in their stored form
     * @param comparisonType the type the comparison is performed as
     * @return the folded operand, empty if the input is empty, or {@code null} if this aggregator cannot fold values
     *         compared as that type
     */
    @Nullable
    Operand apply(@NotNull Operand input, @NotNull OperandType comparisonType);

    /**
     * How the aggregate reads before the operand it folds, e.g. {@code the number of values in}.
     *
     * @return a phrase
     */
    @NotNull
    String getPhrase();

    /**
     * The types of values it can fold.
     *
     * @return the types, an empty set if it folds values of any type
     */
    @NotNull
    default Set<OperandType> getAcceptedTypes()
    {
        return Set.of();
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
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder().add("phrase", getPhrase());
        final OperandType output = getOutputType();
        json.add("output", output == null ? "same" : name(output));
        if (getAcceptedTypes().isEmpty()) {
            return json.add("accepts", "any");
        }
        final JsonArrayBuilder accepts = Json.createArrayBuilder();
        getAcceptedTypes().stream().map(ConditionAggregator::name).sorted().forEach(accepts::add);
        return json.add("accepts", accepts);
    }

    /**
     * A type as the catalogue names it, as stored labels name it too.
     *
     * @param type a type
     * @return its name in lower case, e.g. {@code long}
     */
    @NotNull
    static String name(@NotNull final OperandType type)
    {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
