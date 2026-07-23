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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionEvaluatorImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionEvaluatorImplTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private final SlingContext context = new SlingContext();

    private ConditionEvaluatorImpl evaluator;

    private Resource contextResource;

    @BeforeEach
    void setUp()
        throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class,
            SingleCondition.class, ConditionGroup.class);
        // The bundle plugin only generates the DS metadata at packaging time, so the service is
        // instantiated directly and its references are injected by hand.
        this.evaluator = new ConditionEvaluatorImpl();
        final Field resolvers = ConditionEvaluatorImpl.class.getDeclaredField("resolvers");
        resolvers.setAccessible(true);
        resolvers.set(this.evaluator, List.of(new LiteralOperandResolver()));
        this.contextResource = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, "iap/Entity");
    }

    /**
     * Creates a literal-vs-literal single condition; a {@code null} operand value skips that operand node.
     */
    private Condition createCondition(final String path, final String comparator,
        final Object left, final Object right)
    {
        final Resource resource = this.context.create().resource(path, Map.of(
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, SUPER_TYPE, Condition.RESOURCE_TYPE,
            "comparator", comparator));
        if (left != null) {
            this.context.create().resource(path + "/operandA",
                SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "value", left);
        }
        if (right != null) {
            this.context.create().resource(path + "/operandB",
                SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "value", right);
        }
        return resource.adaptTo(Condition.class);
    }

    private Condition createSingleCondition(final String path, final String comparator, final String[] left,
        final String[] right)
    {
        return this.createCondition(path, comparator, left, right);
    }

    /** Creates a single condition whose first operand is aggregated. */
    private Condition createAggregatedCondition(final String path, final String comparator,
        final Object left, final String aggregate, final Object right)
    {
        final Resource resource = this.context.create().resource(path, Map.of(
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, SUPER_TYPE, Condition.RESOURCE_TYPE,
            "comparator", comparator));
        final Map<String, Object> leftProperties = left == null
            ? Map.of(SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "aggregate", aggregate)
            : Map.of(SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "aggregate", aggregate, "value", left);
        this.context.create().resource(path + "/operandA", leftProperties);
        if (right != null) {
            this.context.create().resource(path + "/operandB",
                SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "value", right);
        }
        return resource.adaptTo(Condition.class);
    }

    @Test
    void missingConditionsAreSatisfied()
    {
        assertTrue(this.evaluator.isSatisfied(null, this.contextResource));
    }

    @Test
    void evaluatesSingleConditions()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createSingleCondition("/c1", "equals", new String[]{ "a" }, new String[]{ "a" }),
            this.contextResource));
        assertFalse(this.evaluator.isSatisfied(
            this.createSingleCondition("/c2", "equals", new String[]{ "a" }, new String[]{ "b" }),
            this.contextResource));
    }

    @Test
    void unifiesTheComparisonTypeFromStoredValueTypes()
    {
        // 9 < 10 as numbers, but "9" > "10" as strings: the typed stored value drives the comparison,
        // and the plain-string side follows it, whichever side each of them is on
        assertTrue(this.evaluator.isSatisfied(
            this.createCondition("/c1", "less than", 9L, new String[]{ "10" }), this.contextResource));
        assertTrue(this.evaluator.isSatisfied(
            this.createCondition("/c2", "less than", new String[]{ "9" }, 10L), this.contextResource));
    }

    @Test
    void widensMismatchedNumericTypes()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createCondition("/c1", "equals", 1L, 1.0d), this.contextResource));
        assertTrue(this.evaluator.isSatisfied(
            this.createCondition("/c2", "equals", 1L, BigDecimal.ONE), this.contextResource));
    }

    @Test
    void failsClosedOnIncompatibleTypes()
    {
        assertFalse(this.evaluator.isSatisfied(
            this.createCondition("/c1", "equals", 9L, Boolean.TRUE), this.contextResource));
    }

    @Test
    void countsValues()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "equals", new String[]{ "a", "b", "c" }, "count",
                new String[]{ "3" }),
            this.contextResource));
    }

    @Test
    void sumsValues()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "equals", new Long[]{ 1L, 2L }, "sum", new String[]{ "3" }),
            this.contextResource));
    }

    @Test
    void averagesValues()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "equals", new Long[]{ 1L, 2L }, "avg", new String[]{ "1.5" }),
            this.contextResource));
    }

    @Test
    void failsClosedWhenTheAggregatorCannotOperate()
    {
        // Summing plain strings: nothing declares a numeric type, so the comparison stays textual
        assertFalse(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "equals", new String[]{ "1", "2" }, "sum",
                new String[]{ "3" }),
            this.contextResource));
    }

    @Test
    void failsClosedOnUnknownAggregators()
    {
        assertFalse(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "equals", new String[]{ "a" }, "median", new String[]{ "a" }),
            this.contextResource));
    }

    @Test
    void aggregatedAbsenceStaysDetectable()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createAggregatedCondition("/c1", "is empty", null, "count", null), this.contextResource));
    }

    @Test
    void failsClosedOnUnknownComparators()
    {
        assertFalse(this.evaluator.isSatisfied(
            this.createSingleCondition("/c1", "resembles", new String[]{ "a" }, new String[]{ "a" }),
            this.contextResource));
    }

    @Test
    void treatsMissingOperandsAsEmpty()
    {
        assertTrue(this.evaluator.isSatisfied(
            this.createSingleCondition("/c1", "is empty", null, null), this.contextResource));
    }

    @Test
    void failsClosedOnUnknownOperandSources()
    {
        final Resource resource = this.context.create().resource("/c1",
            SLING_RESOURCE_TYPE, SingleCondition.RESOURCE_TYPE, "comparator", "is empty");
        this.context.create().resource("/c1/operandA",
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "source", "crystal ball");

        assertFalse(this.evaluator.isSatisfied(resource.adaptTo(Condition.class), this.contextResource));
    }

    @Test
    void failsClosedOnUnknownConditionTypes()
    {
        final Condition unknown = new Condition()
        {
            @Override
            public String getType()
            {
                return "cond/Timer";
            }

            @Override
            public String getPath()
            {
                return "/somewhere";
            }
        };

        assertFalse(this.evaluator.isSatisfied(unknown, this.contextResource));
    }

    @Test
    void combinesGroupsWithOr()
    {
        final Resource group = this.context.create().resource("/g1",
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE);
        this.createSingleCondition("/g1/no", "equals", new String[]{ "a" }, new String[]{ "b" });
        this.createSingleCondition("/g1/yes", "equals", new String[]{ "a" }, new String[]{ "a" });

        assertTrue(this.evaluator.isSatisfied(group.adaptTo(Condition.class), this.contextResource));
    }

    @Test
    void combinesGroupsWithAnd()
    {
        final Resource group = this.context.create().resource("/g1", Map.of(
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE, "requireAll", true));
        this.createSingleCondition("/g1/yes", "equals", new String[]{ "a" }, new String[]{ "a" });
        this.createSingleCondition("/g1/no", "equals", new String[]{ "a" }, new String[]{ "b" });

        assertFalse(this.evaluator.isSatisfied(group.adaptTo(Condition.class), this.contextResource));
    }

    @Test
    void emptyGroupsFollowTheUsualConventions()
    {
        final Resource andGroup = this.context.create().resource("/gAnd", Map.of(
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE, "requireAll", true));
        final Resource orGroup = this.context.create().resource("/gOr",
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE);

        assertTrue(this.evaluator.isSatisfied(andGroup.adaptTo(Condition.class), this.contextResource));
        assertFalse(this.evaluator.isSatisfied(orGroup.adaptTo(Condition.class), this.contextResource));
    }

    @Test
    void recursesIntoNestedGroups()
    {
        final Resource group = this.context.create().resource("/g1", Map.of(
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE, "requireAll", true));
        this.context.create().resource("/g1/inner", Map.of(
            SLING_RESOURCE_TYPE, ConditionGroup.RESOURCE_TYPE, SUPER_TYPE, Condition.RESOURCE_TYPE));
        this.createSingleCondition("/g1/inner/yes", "equals", new String[]{ "a" }, new String[]{ "a" });

        assertTrue(this.evaluator.isSatisfied(group.adaptTo(Condition.class), this.contextResource));
    }

    @Test
    void appliesFollowsTheGuardingCondition()
    {
        final Conditionable unguarded = () -> null;
        assertTrue(this.evaluator.applies(unguarded, this.contextResource));

        final Condition unsatisfied =
            this.createSingleCondition("/c1", "equals", new String[]{ "a" }, new String[]{ "b" });
        final Conditionable guarded = () -> unsatisfied;
        assertFalse(this.evaluator.applies(guarded, this.contextResource));
    }
}
