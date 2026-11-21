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
package io.uhndata.iap.conditions.api;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.spi.CandidateProvider;
import io.uhndata.iap.conditions.spi.ConditionAggregator;
import io.uhndata.iap.conditions.spi.ConditionComparator;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * The registry of the comparators, aggregators and operand sources a condition can name: the built-in ones, and
 * those other modules register as services. The evaluator, the schema checks and the {@code /ConditionTypes}
 * catalogue all read it, so a name the checks accept is one the evaluator can evaluate.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionTypes
{
    /**
     * Finds a comparator by name.
     *
     * @param name a name as stored in a condition's {@code comparator} property
     * @return the comparator, or {@code null} if none has that name
     */
    @Nullable
    ConditionComparator getComparator(@NotNull String name);

    /**
     * Every comparator, the built-in ones first, in the order the condition builder offers them.
     *
     * @return the comparators
     */
    @NotNull
    List<ConditionComparator> getComparators();

    /**
     * Finds an aggregator by name.
     *
     * @param name a name as stored in an operand's {@code aggregate} property
     * @return the aggregator, or {@code null} if none has that name
     */
    @Nullable
    ConditionAggregator getAggregator(@NotNull String name);

    /**
     * Every aggregator, the built-in ones first.
     *
     * @return the aggregators
     */
    @NotNull
    List<ConditionAggregator> getAggregators();

    /**
     * Finds an operand source by name.
     *
     * @param name a name as stored in an operand's {@code source} property
     * @return the source, or {@code null} if none has that name
     */
    @Nullable
    OperandResolver getSource(@NotNull String name);

    /**
     * Every operand source, sorted by {@link OperandResolver#getOrder()}.
     *
     * @return the sources
     */
    @NotNull
    List<OperandResolver> getSources();

    /**
     * Finds the provider that lists the candidates of an operand source.
     *
     * @param source a source name as stored in an operand's {@code source} property, e.g. {@code answer}
     * @return the provider, or {@code null} if no provider lists that source's candidates
     */
    @Nullable
    CandidateProvider getCandidateProvider(@NotNull String source);
}
