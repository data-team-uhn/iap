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
        Thread.currentThread().interrupt();
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
        } finally {
            Thread.interrupted();
        }
        assertTrue(asked.isEmpty(), "the model was never asked");
        assertTrue(ReadingRuns.isStopped(new ReadingRuns.Stopped()));
        assertFalse(ReadingRuns.isStopped(new IOException("closed")));
    }

    @Test
    void turnsACallEndedByAStopIntoAStop()
    {
        final LLMClient client = (system, messages, options) -> {
            Thread.currentThread().interrupt();
            throw new IOException("closed");
        };
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
        } finally {
            Thread.interrupted();
        }
    }

    // Some clients still return a reply after the thread was interrupted, so the reply must be refused
    @Test
    void refusesAReplyThatArrivesAfterAStop()
    {
        final LLMClient client = (system, messages, options) -> {
            Thread.currentThread().interrupt();
            return "too late";
        };
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.ask(client, "system", "user", null));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void letsAModelThatCannotBeReachedFailAsItself()
    {
        final LLMClient client = (system, messages, options) -> {
            throw new IOException("down");
        };

        assertEquals("down", assertThrows(IOException.class, () -> ModelCall.ask(client, "system", "user", null))
            .getMessage());
    }
}
