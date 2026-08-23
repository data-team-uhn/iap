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
package io.uhndata.iap.history.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecordedAction}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RecordedActionTest
{
    private static final RecordedEffect EFFECT =
        new RecordedEffect("uuid", "/content/study", "sub:Submission", "submitted", List.of("status"));

    @Test
    void keepsWhatEveryActionMustSay()
    {
        final RecordedAction action = RecordedAction.by("reviewer1", "submit").build();

        assertEquals("reviewer1", action.getActor());
        assertEquals("submit", action.getOperation());
    }

    @Test
    void insistsOnAnActorAndAnOperation()
    {
        assertThrows(NullPointerException.class, () -> RecordedAction.by(null, "submit"));
        assertThrows(NullPointerException.class, () -> RecordedAction.by("reviewer1", null));
    }

    @Test
    void saysNothingAboutWhatItWasNotTold()
    {
        final RecordedAction action = RecordedAction.by("reviewer1", "submit").build();

        assertNull(action.getOnBehalfOf());
        assertNull(action.getWorkflowInstance());
        assertNull(action.getWorkflowVersion());
        assertNull(action.getActivityId());
        assertNull(action.getActivityLabel());
        assertNull(action.getTaskInstance());
        assertNull(action.getOutcome());
        assertNull(action.getOutcomeNote());
        assertNull(action.getEvent());
        assertNull(action.getComponent());
        assertNull(action.getParentAction());
        assertTrue(action.getEffects().isEmpty());
    }

    @Test
    void collectsEverythingACauseCanHave()
    {
        final RecordedAction action = RecordedAction.by("reviewer1", "decide")
            .onBehalfOf("chair1")
            .workflow("instance", "version")
            .activity("Activity_review", "Review it")
            .task("task", "approved", "with the amendment")
            .event("taskCompleted")
            .component("io.uhndata.iap.test")
            .partOf("parent")
            .affecting(EFFECT)
            .build();

        assertEquals("chair1", action.getOnBehalfOf());
        assertEquals("instance", action.getWorkflowInstance());
        assertEquals("version", action.getWorkflowVersion());
        assertEquals("Activity_review", action.getActivityId());
        assertEquals("Review it", action.getActivityLabel());
        assertEquals("task", action.getTaskInstance());
        assertEquals("approved", action.getOutcome());
        assertEquals("with the amendment", action.getOutcomeNote());
        assertEquals("taskCompleted", action.getEvent());
        assertEquals("io.uhndata.iap.test", action.getComponent());
        assertEquals("parent", action.getParentAction());
        assertEquals(List.of(EFFECT), action.getEffects());
    }

    @Test
    void refusesAnEffectThatIsNotThere()
    {
        assertThrows(NullPointerException.class, () -> RecordedAction.by("a", "b").affecting(null));
    }

    @Test
    void cannotBeChangedThroughTheBuilderAfterwards()
    {
        final RecordedAction.Builder builder = RecordedAction.by("reviewer1", "submit").affecting(EFFECT);
        final RecordedAction action = builder.build();
        builder.affecting(EFFECT);

        assertEquals(1, action.getEffects().size(),
            "An action already described must not gain effects because the builder was reused");
    }
}
