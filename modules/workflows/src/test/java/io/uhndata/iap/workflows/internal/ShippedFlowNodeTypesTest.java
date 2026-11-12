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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the flow-node types the module ships: which attributes of a diagram's element are kept on the node it
 * becomes.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ShippedFlowNodeTypesTest
{
    private static final String PERFORMERS = "{https://iap.uhndata.io/bpmn}performers=performers[]";

    @Test
    void keepsWhoMaySendTheMessageACatchingEventWaitsFor() throws IOException
    {
        assertTrue(listCopiedProperties("MessageIntermediateCatchEvent").contains(PERFORMERS));
    }

    private static List<String> listCopiedProperties(final String type) throws IOException
    {
        try (InputStream entry = Objects.requireNonNull(ShippedFlowNodeTypesTest.class.getResourceAsStream(
            "/SLING-INF/content/WorkflowTypes/WorkflowTypes/" + type + ".json"), "The vocabulary ships the type");
            JsonReader reader = Json.createReader(entry)) {
            return reader.readObject().getJsonArray("properties").getValuesAs(JsonString.class).stream()
                .map(JsonString::getString)
                .collect(Collectors.toList());
        }
    }
}
