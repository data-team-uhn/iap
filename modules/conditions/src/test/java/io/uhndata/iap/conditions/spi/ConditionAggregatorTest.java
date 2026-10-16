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

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionAggregator}'s defaults, through the least an aggregator from another module provides.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ConditionAggregatorTest
{
    private final ConditionAggregator first = new ConditionAggregator()
    {
        @Override
        public String getName()
        {
            return "first";
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
            return "the first of";
        }
    };

    @Test
    void foldsValuesOfAnyTypeByDefault()
    {
        assertTrue(this.first.getAcceptedTypes().isEmpty());
        final JsonObject json = this.first.documentationJsonBuilder().build();
        assertEquals("any", json.getString("accepts"));
        assertEquals("same", json.getString("output"));
        assertEquals(ConditionAggregator.CATEGORY, json.getJsonArray("category").getString(0));
    }
}
