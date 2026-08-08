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
package io.uhndata.iap.workflows.api;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowResult}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class WorkflowResultTest
{
    @Test
    void exposesTheVariablesLeftBehind()
    {
        final WorkflowResult result = new WorkflowResult(Map.of(WorkflowResult.CREATED_PATH, "/Workflows/x"));

        assertEquals("/Workflows/x", result.getVariable(WorkflowResult.CREATED_PATH));
        assertEquals(Map.of(WorkflowResult.CREATED_PATH, "/Workflows/x"), result.getVariables());
        assertNull(result.getVariable("missing"));
    }

    @Test
    void toleratesNullVariableValues()
    {
        // Unlike an event's payload, an execution may legitimately record that something is unset
        final Map<String, Object> variables = new HashMap<>();
        variables.put("outcome", null);

        final WorkflowResult result = new WorkflowResult(variables);

        assertTrue(result.getVariables().containsKey("outcome"));
        assertNull(result.getVariable("outcome"));
    }

    @Test
    void isDetachedAndUnmodifiable()
    {
        final Map<String, Object> source = new HashMap<>(Map.of("a", "before"));
        final WorkflowResult result = new WorkflowResult(source);
        source.put("a", "after");

        assertEquals("before", result.getVariable("a"));
        assertThrows(UnsupportedOperationException.class, () -> result.getVariables().put("a", "hacked"));
    }
}
