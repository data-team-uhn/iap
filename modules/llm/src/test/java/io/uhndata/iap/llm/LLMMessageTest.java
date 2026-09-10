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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LLMMessage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LLMMessageTest
{
    @Test
    void keepsRoleAndContent()
    {
        final LLMMessage message = new LLMMessage("user", "Hello");
        assertEquals("user", message.getRole());
        assertEquals("Hello", message.getContent());
    }

    @Test
    void acceptsNulls()
    {
        final LLMMessage message = new LLMMessage(null, null);
        assertNull(message.getRole());
        assertNull(message.getContent());
    }
}
