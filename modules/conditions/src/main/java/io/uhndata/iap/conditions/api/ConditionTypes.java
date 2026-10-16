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
 * Every comparator, aggregator and operand source this instance knows: the built-in ones, and those other modules
 * register as services. Evaluating a condition, checking one, and documenting what conditions can say all ask here,
 * so a name is known exactly when it can be evaluated.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionTypes
{
    /**
     * The comparator of that name.
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
     * The aggregator of that name.
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
     * The operand source of that name.
     *
     * @param name a name as stored in an operand's {@code source} property
     * @return the source, or {@code null} if none has that name
     */
    @Nullable
    OperandResolver getSource(@NotNull String name);

    /**
     * Every operand source, in their declared order.
     *
     * @return the sources
     */
    @NotNull
    List<OperandResolver> getSources();

    /**
     * What lists the candidates of an operand source, when something does.
     *
     * @param source a source name
     * @return the provider, or {@code null} when nothing lists that source's candidates
     */
    @Nullable
    CandidateProvider getCandidateProvider(@NotNull String source);
}
