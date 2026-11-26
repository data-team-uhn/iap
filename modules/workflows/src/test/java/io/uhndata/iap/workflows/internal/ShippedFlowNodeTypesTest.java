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
package io.uhndata.iap.workflows.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the flow-node types the module ships: which attributes of a diagram's element are kept on the node it
 * becomes.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ShippedFlowNodeTypesTest
{
    @Test
    void keepsWhoMaySendTheMessageACatchingEventWaitsFor() throws IOException
    {
        final JsonObject performers = readEntry("MessageIntermediateCatchEvent").getJsonObject("performers");
        assertEquals("principal", performers.getString("dataType"));
        assertEquals("attribute", performers.getString("xmlLocation"));
        // Any number of principals, as the node type stores them
        assertEquals(0, performers.getInt("maxValues"));
    }

    @Test
    void keepsWhichResourceEachHandlerActingOnOneWorksOn()
    {
        assertAll(Stream.of("AddTag", "RemoveTag", "AddLink", "CopyContent", "ReplaceContent", "UpdateContent",
            "SaveProperties", "StartWorkflow", "CreateWorkflowVersion", "RetireActiveVersions", "SaveWorkflowDiagram")
            .map(type -> (Executable) () -> assertEquals("attribute",
                readEntry(type).getJsonObject("subject").getString("xmlLocation"), type)));
    }

    private static JsonObject readEntry(final String type) throws IOException
    {
        try (InputStream entry = Objects.requireNonNull(ShippedFlowNodeTypesTest.class.getResourceAsStream(
            "/SLING-INF/content/WorkflowTypes/WorkflowTypes/" + type + ".json"), "The vocabulary ships the type");
            JsonReader reader = Json.createReader(entry)) {
            return reader.readObject();
        }
    }
}
