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
package io.uhndata.iap.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LLMRequestOptions}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LLMRequestOptionsTest
{
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    void defaultsOverrideNothing()
    {
        final LLMRequestOptions options = LLMRequestOptions.defaults();
        assertNull(options.getMaxOutputTokens());
        assertNull(options.getResponseSchema());
        assertNull(options.getResponseSchemaName());
        assertFalse(options.hasResponseSchema());
    }

    @Test
    void resolveMaxOutputTokensFallsBackWhenUnset()
    {
        assertEquals(500, LLMRequestOptions.defaults().resolveMaxOutputTokens(500));
    }

    @Test
    void resolveMaxOutputTokensPrefersTheOverride()
    {
        final LLMRequestOptions options = LLMRequestOptions.withMaxOutputTokens(1200);
        assertEquals(1200L, options.getMaxOutputTokens());
        assertEquals(1200, options.resolveMaxOutputTokens(500));
    }

    @Test
    void builderSetsBothOverrides()
    {
        final LLMRequestOptions options = LLMRequestOptions.builder()
            .maxOutputTokens(64)
            .jsonSchema("document_summary", SCHEMA)
            .build();
        assertEquals(64L, options.getMaxOutputTokens());
        assertEquals("document_summary", options.getResponseSchemaName());
        assertEquals(SCHEMA, options.getResponseSchema());
        assertTrue(options.hasResponseSchema());
    }

    @Test
    void aSchemaNeedsBothANameAndABody()
    {
        assertFalse(LLMRequestOptions.builder().jsonSchema(null, SCHEMA).build().hasResponseSchema());
        assertFalse(LLMRequestOptions.builder().jsonSchema("  ", SCHEMA).build().hasResponseSchema());
        assertFalse(LLMRequestOptions.builder().jsonSchema("name", null).build().hasResponseSchema());
        assertFalse(LLMRequestOptions.builder().jsonSchema("name", "  ").build().hasResponseSchema());
    }
}
