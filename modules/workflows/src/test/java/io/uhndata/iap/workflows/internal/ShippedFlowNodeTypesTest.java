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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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
 * becomes, and which entry an element changed to an element type becomes.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ShippedFlowNodeTypesTest
{
    private static final String VOCABULARY = "/SLING-INF/content/WorkflowTypes/WorkflowTypes/";

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

    @Test
    void flagsOneDefaultEntryForTheElementTypesThatNeedOne() throws IOException, URISyntaxException
    {
        // The labels of these element types sort a less likely entry first, which the flag overrules
        final Map<String, List<String>> flagged = flaggedByElementType();

        assertEquals(Map.of("Gateway", List.of("ExclusiveGateway"),
            "Intermediate Event", List.of("IntermediateThrowEvent")), flagged);
    }

    /** The entries flagged as their element type's default, by element type. */
    private static Map<String, List<String>> flaggedByElementType() throws IOException, URISyntaxException
    {
        final Map<String, List<String>> flagged = new TreeMap<>();
        try (Stream<Path> files = Files.list(Path.of(Objects.requireNonNull(ShippedFlowNodeTypesTest.class
            .getResource(VOCABULARY), "The vocabulary is shipped").toURI()))) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                final String type = file.getFileName().toString().replace(".json", "");
                final JsonObject entry = readEntry(type);
                if (entry.getBoolean("elementTypeDefault", false)) {
                    flagged.computeIfAbsent(entry.getJsonArray("category").getString(0), key -> new ArrayList<>())
                        .add(type);
                }
            }
        }
        return flagged;
    }

    private static JsonObject readEntry(final String type) throws IOException
    {
        try (InputStream entry = Objects.requireNonNull(ShippedFlowNodeTypesTest.class.getResourceAsStream(
            VOCABULARY + type + ".json"), "The vocabulary ships the type");
            JsonReader reader = Json.createReader(entry)) {
            return reader.readObject();
        }
    }
}
