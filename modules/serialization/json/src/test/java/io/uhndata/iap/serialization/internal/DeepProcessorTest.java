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

package io.uhndata.iap.serialization.internal;

import javax.jcr.Node;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link DeepProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DeepProcessorTest
{
    private static final JsonValue SERIALIZED = Json.createValue("the serialized node");

    private final DeepProcessor processor = new DeepProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("deep", this.processor.getName());
        Assertions.assertEquals(10, this.processor.getPriority());
        // Deep serialization must be explicitly requested
        Assertions.assertFalse(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testExistingInputIsLeftUnmodified()
    {
        final JsonValue input = Json.createValue("already computed");

        Assertions.assertSame(input,
            this.processor.processChild(null, Mockito.mock(Node.class), input, n -> SERIALIZED));
    }

    @Test
    public void testChildIsSerialized()
    {
        Assertions.assertSame(SERIALIZED,
            this.processor.processChild(null, Mockito.mock(Node.class), null, n -> SERIALIZED));
    }
}
