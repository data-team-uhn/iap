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
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.conditions.api.Aggregator;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.api.Operator;
import io.uhndata.iap.conditions.spi.Candidate;
import io.uhndata.iap.conditions.spi.CandidateProvider;
import io.uhndata.iap.conditions.spi.ConditionAggregator;
import io.uhndata.iap.conditions.spi.ConditionComparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ConditionTypesImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ConditionTypesImplTest
{
    private final ConditionTypesImpl types = new ConditionTypesImpl();

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        // Registered after the built-ins, and one of each claims a built-in name
        inject("comparators", List.of(comparator("resembles"), comparator("equals")));
        inject("aggregators", List.of(aggregator("median"), aggregator("count")));
        inject("sources", List.of(new PropertyOperandResolver(), new TagsOperandResolver(),
            new AnswerOperandResolver()));
    }

    @Test
    void listsTheBuiltInComparatorsFirstThenTheRegisteredOnes()
    {
        final List<ConditionComparator> comparators = this.types.getComparators();
        assertEquals(Operator.values().length + 1, comparators.size());
        assertSame(Operator.EQUALS, comparators.get(0));
        assertEquals("resembles", comparators.get(comparators.size() - 1).getName());
    }

    @Test
    void aBuiltInNameStaysTheBuiltIn()
    {
        assertSame(Operator.EQUALS, this.types.getComparator("equals"));
        assertSame(Aggregator.COUNT, this.types.getAggregator("count"));
    }

    @Test
    void findsRegisteredOnesByName()
    {
        assertEquals("resembles", this.types.getComparator("resembles").getName());
        assertEquals("median", this.types.getAggregator("median").getName());
        assertEquals(Aggregator.values().length + 1, this.types.getAggregators().size());
        assertEquals("tags", this.types.getSource("tags").getSource());
    }

    @Test
    void knowsNothingByAnUnknownName()
    {
        assertNull(this.types.getComparator("rhymes with"));
        assertNull(this.types.getAggregator("mode"));
        assertNull(this.types.getSource("weather"));
    }

    @Test
    void findsWhatListsASourcesCandidates() throws ReflectiveOperationException
    {
        final CandidateProvider questions =
            new CandidateProvider()
            {
                @Override
                public String getSource()
                {
                    return "answer";
                }

                @Override
                public List<Candidate> getCandidates(
                    final Resource context)
                {
                    return List.of();
                }
            };
        inject("providers", List.of(questions));
        assertSame(questions, this.types.getCandidateProvider("answer"));
        assertNull(this.types.getCandidateProvider("tags"));
    }

    @Test
    void listsTheSourcesInTheirDeclaredOrder()
    {
        assertEquals(List.of("answer", "tags", "property"),
            this.types.getSources().stream().map(source -> source.getSource()).toList());
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = ConditionTypesImpl.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.types, value);
    }

    private static ConditionComparator comparator(final String name)
    {
        return new ConditionComparator()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getDescription()
            {
                return null;
            }

            @Override
            public String getPhrase()
            {
                return name;
            }

            @Override
            public boolean evaluate(final Operand left, final Operand right)
            {
                return false;
            }
        };
    }

    private static ConditionAggregator aggregator(final String name)
    {
        return new ConditionAggregator()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getDescription()
            {
                return null;
            }

            @Override
            public OperandType getOutputType()
            {
                return null;
            }

            @Override
            public Operand apply(final Operand input, final OperandType comparisonType)
            {
                return input;
            }

            @Override
            public String getPhrase()
            {
                return name;
            }
        };
    }
}
