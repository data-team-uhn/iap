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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.conditions.api.Aggregator;
import io.uhndata.iap.conditions.api.ConditionTypes;
import io.uhndata.iap.conditions.api.Operator;
import io.uhndata.iap.conditions.spi.ConditionAggregator;
import io.uhndata.iap.conditions.spi.ConditionComparator;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * The built-in comparators and aggregators, with those registered as services after them, and every registered
 * operand source. A registered one with a built-in name is ignored: the built-in one wins.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ConditionTypes.class)
public class ConditionTypesImpl implements ConditionTypes
{
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<ConditionComparator> comparators = List.of();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<ConditionAggregator> aggregators = List.of();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<OperandResolver> sources = List.of();

    @Override
    public ConditionComparator getComparator(final String name)
    {
        return named(getComparators(), name, ConditionComparator::getName);
    }

    @Override
    public List<ConditionComparator> getComparators()
    {
        return withBuiltIns(Arrays.asList(Operator.values()), this.comparators, ConditionComparator::getName);
    }

    @Override
    public ConditionAggregator getAggregator(final String name)
    {
        return named(getAggregators(), name, ConditionAggregator::getName);
    }

    @Override
    public List<ConditionAggregator> getAggregators()
    {
        return withBuiltIns(Arrays.asList(Aggregator.values()), this.aggregators, ConditionAggregator::getName);
    }

    @Override
    public OperandResolver getSource(final String name)
    {
        return named(this.sources, name, OperandResolver::getSource);
    }

    @Override
    public List<OperandResolver> getSources()
    {
        return this.sources.stream()
            .sorted(Comparator.comparingInt(OperandResolver::getOrder).thenComparing(OperandResolver::getSource))
            .toList();
    }

    private static <T> List<T> withBuiltIns(final List<? extends T> builtIns, final List<? extends T> registered,
        final Function<T, String> nameOf)
    {
        return Stream.concat(builtIns.stream(), registered.stream()
            .filter(item -> builtIns.stream().noneMatch(builtIn -> nameOf.apply(builtIn).equals(nameOf.apply(item)))))
            .toList();
    }

    private static <T> T named(final List<T> items, final String name, final Function<T, String> nameOf)
    {
        return items.stream().filter(item -> Objects.equals(nameOf.apply(item), name)).findFirst().orElse(null);
    }
}
