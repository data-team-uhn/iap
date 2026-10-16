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
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OperandResolver}'s descriptive defaults, through the least a source from another module
 * provides.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OperandResolverTest
{
    private final OperandResolver weather = new OperandResolver()
    {
        @Override
        public String getSource()
        {
            return "weather";
        }

        @Override
        public Operand resolve(final ConditionOperand operand, final Content context)
        {
            return Operand.EMPTY;
        }
    };

    @Test
    void isNamedAfterItsSourceAndOfferedFirstByDefault()
    {
        assertEquals("weather", this.weather.getName());
        assertEquals("weather", this.weather.getDocumentationLabel());
        assertNull(this.weather.getDescription());
        assertNull(this.weather.getValueLabel());
        assertNull(this.weather.getValueType());
        assertNull(this.weather.isMultiple());
        assertNull(this.weather.getChoices());
        assertEquals("{label}", this.weather.getPhrase());
        assertTrue(this.weather.isOffered());
        assertEquals(0, this.weather.getOrder());
    }

    @Test
    void leavesOutWhatItCannotTell()
    {
        final JsonObject json = this.weather.documentationJsonBuilder().build();
        assertEquals(OperandResolver.CATEGORY, json.getJsonArray("category").getString(0));
        assertTrue(json.getBoolean("offered"));
        assertFalse(json.containsKey("valueLabel"));
        assertFalse(json.containsKey("type"));
        assertFalse(json.containsKey("multiple"));
        assertFalse(json.containsKey("choices"));
    }
}
