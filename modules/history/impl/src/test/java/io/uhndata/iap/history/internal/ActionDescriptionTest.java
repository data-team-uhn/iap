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
package io.uhndata.iap.history.internal;

import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ActionDescription}: what a session carries, and what is not one.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ActionDescriptionTest
{
    private static final RecordedAction EVERYTHING = RecordedAction.by("jdoe", "approve")
        .onBehalfOf("reviewer@example.org")
        .workflow("/Requests/one/wf:instances/process", "version-id")
        .activity("approve", "Approve the request")
        .task("/Requests/one/wf:instances/process/approve", "approved", "Looks right")
        .event("complete")
        .component("io.uhndata.iap.Sweeper")
        .partOf("/History/aa/bb/cc/parent")
        .occurredAt(new GregorianCalendar(2026, 0, 15))
        .snapshotsFollow()
        .affecting(new RecordedEffect("/Requests/one", "approved"))
        .build();

    @Test
    void carriesEveryPartOfTheCause()
    {
        final ActionDescription described = ActionDescription.of("name", EVERYTHING);

        assertEquals(Map.ofEntries(Map.entry("createdBy", "jdoe"), Map.entry("operation", "approve"),
            Map.entry("onBehalfOf", "reviewer@example.org"),
            Map.entry("workflowInstance", "/Requests/one/wf:instances/process"),
            Map.entry("workflowVersion", "version-id"), Map.entry("activityId", "approve"),
            Map.entry("activityLabel", "Approve the request"),
            Map.entry("taskInstance", "/Requests/one/wf:instances/process/approve"),
            Map.entry("outcome", "approved"), Map.entry("outcomeNote", "Looks right"), Map.entry("event", "complete"),
            Map.entry("component", "io.uhndata.iap.Sweeper"), Map.entry("parentAction", "/History/aa/bb/cc/parent")),
            described.cause());
        assertTrue(described.occurredAt().startsWith("2026-01-15T00:00:00"));
        assertFalse(described.complete(), "Snapshots were promised");
        assertEquals(List.of(new RecordedEffect("/Requests/one", "approved")), described.effects());
    }

    @Test
    void leavesOutWhatTheCauseDidNotSay()
    {
        final ActionDescription described = ActionDescription.of("name", RecordedAction.by("jdoe", "save").build());

        assertEquals(Map.of("createdBy", "jdoe", "operation", "save"), described.cause());
        assertNull(described.occurredAt());
        assertTrue(described.complete());
    }

    @Test
    void readsBackWhatItWrote()
    {
        final ActionDescription everything = ActionDescription.of("name", EVERYTHING);
        final ActionDescription least = ActionDescription.of("other", RecordedAction.by("jdoe", "save").build());

        assertEquals(everything, ActionDescription.fromUserData(everything.toUserData()).orElseThrow());
        assertEquals(least, ActionDescription.fromUserData(least.toUserData()).orElseThrow());
    }

    @Test
    void takesNothingElseForADescription()
    {
        assertTrue(ActionDescription.fromUserData(null).isEmpty(), "no user data");
        assertTrue(ActionDescription.fromUserData(42).isEmpty(), "not text");
        assertTrue(ActionDescription.fromUserData("a plain note").isEmpty(), "text without the key");
        assertTrue(ActionDescription.fromUserData("{\"note\": \"iap:history\"}").isEmpty(), "the key as a value");
        assertTrue(ActionDescription.fromUserData("\"iap:history\"").isEmpty(), "JSON that is not an object");
        assertTrue(ActionDescription.fromUserData("iap:history, not JSON").isEmpty(), "not JSON");
    }
}
