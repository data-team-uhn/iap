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
package io.uhndata.iap.llm;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link DefaultLLMClient}, checking that every chat overload reaches the single
 * {@code doChat} hook with the arguments the subclass expects.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DefaultLLMClientTest
{
    private RecordingClient client;

    /**
     * A concrete client that records what it was asked to send instead of calling a model.
     */
    private static final class RecordingClient extends DefaultLLMClient
    {
        private String systemPrompt;

        private List<LLMMessage> messages;

        private LLMRequestOptions options;

        @Override
        protected String doChat(final String system, final List<LLMMessage> conversation,
            final LLMRequestOptions requestOptions)
        {
            this.systemPrompt = system;
            this.messages = conversation;
            this.options = requestOptions;
            return "reply";
        }

        LLMConfigurationService configurationService()
        {
            return getConfigurationService();
        }
    }

    @BeforeEach
    void setUp()
    {
        this.client = new RecordingClient();
    }

    @Test
    void aUserMessageAloneIsSentWithNoSystemPromptAndNoOptions() throws IOException
    {
        assertEquals("reply", this.client.chat("Hello"));

        assertNull(this.client.systemPrompt);
        assertNull(this.client.options);
        assertEquals(1, this.client.messages.size());
        assertEquals("user", this.client.messages.get(0).getRole());
        assertEquals("Hello", this.client.messages.get(0).getContent());
    }

    @Test
    void aSystemPromptAndAUserMessageBecomeASingleTurn() throws IOException
    {
        assertEquals("reply", this.client.chat("Be brief", "Hello"));

        assertEquals("Be brief", this.client.systemPrompt);
        assertNull(this.client.options);
        assertEquals(1, this.client.messages.size());
        assertEquals("Hello", this.client.messages.get(0).getContent());
    }

    @Test
    void aConversationIsPassedThroughUnchanged() throws IOException
    {
        final List<LLMMessage> conversation =
            List.of(new LLMMessage("user", "Hello"), new LLMMessage("assistant", "Hi"));

        assertEquals("reply", this.client.chat("Be brief", conversation));

        assertEquals("Be brief", this.client.systemPrompt);
        assertSame(conversation, this.client.messages);
        assertNull(this.client.options);
    }

    @Test
    void perCallOptionsReachTheSubclass() throws IOException
    {
        final List<LLMMessage> conversation = List.of(new LLMMessage("user", "Hello"));
        final LLMRequestOptions options = LLMRequestOptions.withMaxOutputTokens(42);

        assertEquals("reply", this.client.chat("Be brief", conversation, options));

        assertSame(options, this.client.options);
        assertSame(conversation, this.client.messages);
    }

    @Test
    void exposesTheConfigurationServiceTheComponentBound()
    {
        assertNull(this.client.configurationService());

        final LLMSettings settings = new LLMSettings("local", Map.of(), "llama3.2-3b", Map.of());
        final LLMConfigurationService service = () -> settings;
        this.client.setConfigurationService(service);

        assertSame(service, this.client.configurationService());
    }
}
