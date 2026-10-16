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

import java.util.List;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.conditions.api.OperandType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Candidate}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CandidateTest
{
    @Test
    void saysWhatTheOperandThenHolds()
    {
        final JsonObject json = new Candidate("what", "What do you need?", OperandType.TEXT, false,
            List.of(new Candidate.Choice("laptop", "Laptop"))).toJson();
        assertEquals("what", json.getString("value"));
        assertEquals("What do you need?", json.getString("label"));
        assertEquals("text", json.getString("type"));
        assertFalse(json.getBoolean("multiple"));
        assertEquals("Laptop", json.getJsonArray("choices").getJsonObject(0).getString("label"));
    }

    @Test
    void leavesOutWhatIsUnknown()
    {
        final JsonObject json = new Candidate("status", "status", null, null, null).toJson();
        assertFalse(json.containsKey("type"));
        assertFalse(json.containsKey("multiple"));
        assertFalse(json.containsKey("choices"));
        assertTrue(new Candidate("status", "status", null, null, null).choices().isEmpty());
    }

    @Test
    void saysWhatElseNamesItAndWhatTellsItApart()
    {
        final JsonObject json = new Candidate("u-what", "What?", null, null, List.of(), List.of("form/what"), "what")
            .toJson();
        assertEquals("form/what", json.getJsonArray("aliases").getString(0));
        assertEquals("what", json.getString("detail"));
        assertTrue(new Candidate("v", "l", null, null, null, null, null).aliases().isEmpty());
        assertFalse(new Candidate("v", "l", null, null, null).toJson().containsKey("aliases"));
        assertFalse(new Candidate("v", "l", null, null, null).toJson().containsKey("detail"));
    }

    @Test
    void requiresAValueAndALabel()
    {
        assertThrows(NullPointerException.class, () -> new Candidate(null, "label", null, null, List.of()));
        assertThrows(NullPointerException.class, () -> new Candidate("value", null, null, null, List.of()));
    }
}
