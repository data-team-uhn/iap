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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.io.InputStream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the system workflows a reading job's events start against the code. {@link ReadingJobsTest} sends each
 * event straight to its handler, so a workflow that names another event or handler would only fail at run time.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReadingWorkflowsTest
{
    private static JsonObject readVersion(final String workflow) throws IOException
    {
        try (InputStream content = ReadingWorkflowsTest.class.getResourceAsStream(
            "/SLING-INF/content/SystemWorkflows/" + workflow + ".json")) {
            assertNotNull(content, workflow);
            try (JsonReader reader = Json.createReader(content)) {
                return reader.readObject().getJsonObject("v1");
            }
        }
    }

    private static JsonObject findNode(final JsonObject version, final String type)
    {
        return version.values().stream()
            .filter(JsonObject.class::isInstance)
            .map(JsonObject.class::cast)
            .filter(node -> type.equals(node.getString("jcr:primaryType", null)))
            .findFirst()
            .orElseThrow();
    }

    private static void assertStartsOn(final JsonObject version, final String event)
    {
        final JsonObject start = findNode(version, "wf:StartEvent");
        assertEquals(event, start.getString("messageName"));
        assertTrue(start.getJsonArray("performers").getValuesAs(JsonString::getString)
            .contains(IntakeTasks.READER_USER), "the job sends it as the extraction user");
    }

    @Test
    void claimsTheReadingOnTheEventAJobSendsToStart() throws IOException
    {
        final JsonObject version = readVersion("readingStarted");

        assertStartsOn(version, ReadingJobs.READING_STARTED_EVENT);
        assertEquals(ClaimReadingHandler.HANDLER_NAME, findNode(version, "wf:Activity").getString("handler"));
    }

    @Test
    void givesUpTheReadingOnTheEventAJobSendsToGiveUp() throws IOException
    {
        final JsonObject version = readVersion("readingFailed");

        assertStartsOn(version, ReadingJobs.READING_FAILED_EVENT);
        assertEquals(GiveUpReadingHandler.HANDLER_NAME, findNode(version, "wf:Activity").getString("handler"));
    }
}
