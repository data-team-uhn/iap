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
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link IdentificationProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class IdentificationProcessorTest
{
    private final IdentificationProcessor processor = new IdentificationProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("identify", this.processor.getName());
        Assertions.assertEquals(10, this.processor.getPriority());
        Assertions.assertTrue(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testPathAndNameAreAdded()
        throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn("/Extensions/DashboardWidget/Welcome");
        Mockito.when(node.getName()).thenReturn("Welcome");
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.leave(node, json, null);

        final JsonObject result = json.build();
        Assertions.assertEquals("/Extensions/DashboardWidget/Welcome", result.getString("@path"));
        Assertions.assertEquals("Welcome", result.getString("@name"));
    }

    @Test
    public void testInaccessibleNodeIsIgnored()
        throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenThrow(new RepositoryException());
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.leave(node, json, null);

        Assertions.assertTrue(json.build().isEmpty());
    }
}
