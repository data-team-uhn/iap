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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ConditionComparator}'s defaults, through the least a comparator from another module provides.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ConditionComparatorTest
{
    private final ConditionComparator resembles = new ConditionComparator()
    {
        @Override
        public String getName()
        {
            return "resembles";
        }

        @Override
        public String getDescription()
        {
            return null;
        }

        @Override
        public String getPhrase()
        {
            return "resembles";
        }

        @Override
        public boolean evaluate(final Operand left, final Operand right)
        {
            return true;
        }
    };

    @Test
    void takesTwoOperandsOfAnyShapeByDefault()
    {
        assertFalse(this.resembles.isUnary());
        assertFalse(this.resembles.isOrdering());
        assertFalse(this.resembles.isSets());
        assertNull(this.resembles.getSingleValuePhrase());
    }

    @Test
    void isDocumentedAsAComparator()
    {
        final JsonObject json = this.resembles.documentationJsonBuilder().build();
        assertEquals("resembles", json.getString("label"));
        assertEquals(ConditionComparator.CATEGORY, json.getJsonArray("category").getString(0));
        assertFalse(json.containsKey("singleValuePhrase"));
    }
}
