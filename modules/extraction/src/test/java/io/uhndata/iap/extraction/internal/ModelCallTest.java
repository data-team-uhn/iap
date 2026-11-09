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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.llm.LLMClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModelCall}: sending one message, and how a stop ends a call.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ModelCallTest
{
    private static final String SUBMISSION = "/Submissions/aa/bb/cc/proposal-1";

    private final ReadingRuns runs = new ReadingRuns();

    @BeforeEach
    void beginReading()
    {
        this.runs.begin(SUBMISSION);
    }

    @AfterEach
    void endReading()
    {
        this.runs.end(SUBMISSION);
        Thread.interrupted();
    }

    @Test
    void sendsTheMessageAndReturnsTheReply() throws IOException
    {
        final List<String> asked = new ArrayList<>();
        final LLMClient client = (system, messages, options) -> {
            asked.add(system + "|" + messages.get(0).getRole() + "|" + messages.get(0).getContent());
            return "yes";
        };

        assertEquals("yes", ModelCall.ask(client, "system", "user message", null));
        assertEquals(List.of("system|user|user message"), asked);
    }

    @Test
    void doesNotAskWhenTheReadingWasStopped()
    {
        final List<String> asked = new ArrayList<>();
        final LLMClient client = (system, messages, options) -> {
            asked.add("asked");
            return "{}";
        };
        this.runs.stop(SUBMISSION);

        assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
        assertTrue(asked.isEmpty(), "the model was never asked");
        assertTrue(ReadingRuns.isStopped(new ReadingRuns.Stopped()));
    }

    @Test
    void turnsACallEndedByAStopIntoAStop()
    {
        final LLMClient client = (system, messages, options) -> {
            this.runs.stop(SUBMISSION);
            throw new IOException("closed");
        };

        assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
        assertFalse(Thread.currentThread().isInterrupted(), "taken down before the thread goes back to work");
    }

    // Some clients still return a reply after the thread was interrupted, so the reply must be refused
    @Test
    void refusesAReplyThatArrivesAfterAStop()
    {
        final LLMClient client = (system, messages, options) -> {
            this.runs.stop(SUBMISSION);
            return "too late";
        };

        assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
    }

    @Test
    void letsAModelThatCannotBeReachedFailAsItself()
    {
        final LLMClient client = (system, messages, options) -> {
            throw new IOException("down");
        };

        assertEquals("down", assertThrows(IOException.class, () -> ModelCall.ask(client, "system", "user", null))
            .getMessage());
        assertFalse(ReadingRuns.isStopped(new IOException("down")));
    }

    // Left marked as calling, the thread would be interrupted by a later stop, in whatever job it runs next
    @Test
    void leavesTheCallWhateverTheClientThrows()
    {
        final LLMClient client = (system, messages, options) -> {
            throw new IllegalStateException("broken client");
        };

        assertThrows(IllegalStateException.class, () -> ModelCall.ask(client, "system", "user", null));
        this.runs.stop(SUBMISSION);
        assertFalse(Thread.currentThread().isInterrupted(), "a stop outside a call does not interrupt");
    }

    // An interrupt from elsewhere, such as the instance shutting down, is not the submitter's stop
    @Test
    void asksEvenWhenTheThreadWasInterruptedByAnythingButAStop() throws IOException
    {
        Thread.currentThread().interrupt();

        assertEquals("yes", ModelCall.ask((system, messages, options) -> "yes", "system", "user", null));
    }
}
