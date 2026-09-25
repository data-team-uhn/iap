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
import java.util.List;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModelCall}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ModelCallTest
{
    @Test
    void doesNotAskWhenTheReadingWasStopped()
    {
        Thread.currentThread().interrupt();
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.askWithCorrection(new Answering("{\"ok\":true}"),
                "system", "user", null, reply -> reply, null, "intake"));
        } finally {
            Thread.interrupted();
        }
        assertTrue(ReadingRuns.isStopped(new ReadingRuns.Stopped()));
        assertFalse(ReadingRuns.isStopped(new IOException("closed")));
    }

    @Test
    void aStoppedCallIsNotAskedAgain()
    {
        final LLMClient client = (system, messages, options) -> {
            Thread.currentThread().interrupt();
            throw new IOException("closed");
        };
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> ModelCall.askWithCorrection(client,
                "system", "user", null, reply -> null, null, "intake"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aModelThatCannotBeReachedIsNotAStop()
    {
        final LLMClient client = (system, messages, options) -> {
            throw new IOException("down");
        };

        assertThrows(IOException.class, () -> ModelCall.askWithCorrection(client,
            "system", "user", null, reply -> reply, null, "intake"));
    }

    @Test
    void tellsTheModelWhatWasWrong() throws IOException
    {
        final LLMClient client = new LLMClient()
        {
            private int calls;

            @Override
            public String chat(final String systemPrompt, final List<LLMMessage> messages,
                final LLMRequestOptions options)
            {
                return ++this.calls == 1 ? "bad" : "good";
            }
        };

        assertEquals("good", ModelCall.askWithCorrection(client, "system", "user", null,
            reply -> "bad".equals(reply) ? null : reply, reply -> "say it plainly", "intake"));
    }

    @Test
    void aCorrectionWithNothingSpecificStillAsksAgain() throws IOException
    {
        final LLMClient client = new LLMClient()
        {
            private int calls;

            @Override
            public String chat(final String systemPrompt, final List<LLMMessage> messages,
                final LLMRequestOptions options)
            {
                return ++this.calls == 1 ? "bad" : "good";
            }
        };

        assertEquals("good", ModelCall.askWithCorrection(client, "system", "user", null,
            reply -> "bad".equals(reply) ? null : reply, null, "intake"));
    }

    @Test
    void readsAWellFormedAnswer() throws IOException
    {
        assertEquals("yes", ModelCall.askWithCorrection(new Answering("yes"),
            "system", "user", null, reply -> reply, null, "intake"));
    }

    private static final class Answering implements LLMClient
    {
        private final String reply;

        private Answering(final String reply)
        {
            this.reply = reply;
        }

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages,
            final LLMRequestOptions options)
        {
            return this.reply;
        }
    }
}
