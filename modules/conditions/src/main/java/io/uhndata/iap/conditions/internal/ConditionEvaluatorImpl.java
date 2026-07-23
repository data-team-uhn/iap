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

import java.util.List;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Aggregator;
import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.api.Operator;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * Straightforward implementation of {@link ConditionEvaluator}: single conditions are compared through the
 * {@link Operator} matching their comparator, with each operand resolved by the registered {@link OperandResolver}
 * matching its source; condition groups recurse into their nested conditions and combine them with AND or OR.
 * There is no declared comparison type: the two sides are unified from what is known about them — the type a
 * resolver declares (e.g. the referenced question's data type), or failing that the intrinsic type of the stored
 * values — then both are coerced to the unified type, aggregated if requested, and compared.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class ConditionEvaluatorImpl implements ConditionEvaluator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConditionEvaluatorImpl.class);

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<OperandResolver> resolvers;

    @Override
    public boolean isSatisfied(final Condition condition, final Resource context)
    {
        if (condition == null) {
            return true;
        }
        if (condition instanceof ConditionGroup) {
            return this.isSatisfied((ConditionGroup) condition, context);
        }
        if (condition instanceof SingleCondition) {
            return this.isSatisfied((SingleCondition) condition, context);
        }
        LOGGER.warn("No evaluation logic for condition type {} at {}", condition.getType(), condition.getPath());
        return false;
    }

    @Override
    public boolean applies(final Conditionable conditionable, final Resource context)
    {
        return this.isSatisfied(conditionable.getCondition(), context);
    }

    private boolean isSatisfied(final ConditionGroup group, final Resource context)
    {
        // With no nested conditions an AND group holds and an OR group doesn't,
        // following the usual empty-conjunction/disjunction conventions.
        return group.isRequireAll()
            ? group.getConditions().stream().allMatch(condition -> this.isSatisfied(condition, context))
            : group.getConditions().stream().anyMatch(condition -> this.isSatisfied(condition, context));
    }

    private boolean isSatisfied(final SingleCondition condition, final Resource context)
    {
        final Operator operator;
        final Aggregator leftAggregator;
        final Aggregator rightAggregator;
        try {
            operator = Operator.parse(condition.getComparator());
            leftAggregator = aggregatorOf(condition.getOperandA());
            rightAggregator = aggregatorOf(condition.getOperandB());
        } catch (final IllegalArgumentException ex) {
            LOGGER.warn("Cannot evaluate condition at {}: {}", condition.getPath(), ex.getMessage());
            return false;
        }
        final Optional<Operand> leftRaw = this.resolve(condition.getOperandA(), context);
        final Optional<Operand> rightRaw = this.resolve(condition.getOperandB(), context);
        if (leftRaw.isEmpty() || rightRaw.isEmpty()) {
            return false;
        }
        final OperandType type =
            unify(outputType(leftRaw.get(), leftAggregator), outputType(rightRaw.get(), rightAggregator));
        if (type == null) {
            LOGGER.warn("Cannot evaluate condition at {}: the operands hold incompatible types",
                condition.getPath());
            return false;
        }
        final Operand left = process(leftRaw.get(), leftAggregator, type);
        final Operand right = process(rightRaw.get(), rightAggregator, type);
        if (left == null || right == null) {
            LOGGER.warn("Cannot evaluate condition at {}: the aggregator cannot operate on {} values",
                condition.getPath(), type);
            return false;
        }
        return operator.evaluate(left, right);
    }

    private Optional<Operand> resolve(final ConditionOperand operand, final Resource context)
    {
        if (operand == null) {
            return Optional.of(Operand.EMPTY);
        }
        final Optional<Operand> resolved = this.resolvers.stream()
            .filter(resolver -> operand.getSource().equals(resolver.getSource()))
            .findFirst()
            .map(resolver -> resolver.resolve(operand, context));
        if (resolved.isEmpty()) {
            LOGGER.warn("No resolver for operand source {} at {}", operand.getSource(), operand.getPath());
        }
        return resolved;
    }

    private static Aggregator aggregatorOf(final ConditionOperand operand)
    {
        return operand == null || operand.getAggregate() == null ? null : Aggregator.parse(operand.getAggregate());
    }

    /**
     * The type one side of the comparison brings to unification: what its aggregator will produce, or the best
     * known type of its values.
     */
    private static OperandType outputType(final Operand operand, final Aggregator aggregator)
    {
        if (aggregator != null && aggregator.getOutputType() != null) {
            return aggregator.getOutputType();
        }
        return operand.getEffectiveType();
    }

    /**
     * The single type the comparison is performed as. A side of unknown type follows the other side; two numeric
     * types widen to the larger one; anything else mismatched is not comparable.
     */
    private static OperandType unify(final OperandType left, final OperandType right)
    {
        if (left == null) {
            return right == null ? OperandType.TEXT : right;
        }
        if (right == null || left == right) {
            return left;
        }
        if (left.isNumeric() && right.isNumeric()) {
            return left == OperandType.DECIMAL || right == OperandType.DECIMAL
                ? OperandType.DECIMAL : OperandType.DOUBLE;
        }
        return null;
    }

    private static Operand process(final Operand operand, final Aggregator aggregator, final OperandType type)
    {
        return aggregator == null ? operand.coerce(type) : aggregator.apply(operand, type);
    }
}
