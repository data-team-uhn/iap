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
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.Aggregator;
import io.uhndata.iap.conditions.api.Operator;
import io.uhndata.iap.schemas.spi.SchemaValidityCheck;

/**
 * Check that every condition uses known comparators and aggregators.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SchemaValidityCheck.class)
public class ConditionOperationsCheck implements SchemaValidityCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        return Stream.concat(
            DraftParts.ofType(version, "cond:SingleCondition").flatMap(condition -> comparator(condition, version)),
            DraftParts.ofType(version, "cond:ConditionOperand").flatMap(operand -> aggregate(operand, version)))
            .toList();
    }

    private static Stream<String> comparator(final Resource condition, final Resource version)
    {
        final String comparator = condition.getValueMap().get("comparator", "");
        return knows(Operator::parse, comparator) ? Stream.empty()
            : Stream.of(where(condition, version) + " has a condition comparing with \"" + comparator
                + "\", which is not a known comparison");
    }

    private static Stream<String> aggregate(final Resource operand, final Resource version)
    {
        final String aggregate = operand.getValueMap().get("aggregate", String.class);
        return aggregate == null || knows(Aggregator::parse, aggregate) ? Stream.empty()
            : Stream.of(where(operand, version) + " has a condition aggregating with \"" + aggregate
                + "\", which is not a known aggregate");
    }

    private static boolean knows(final Consumer<String> parser, final String name)
    {
        try {
            parser.accept(name);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private static String where(final Resource conditionPart, final Resource version)
    {
        return DraftParts.describe(DraftParts.conditioned(conditionPart), version);
    }
}
