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
package io.uhndata.iap.llm.internal;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMReply;

/**
 * Translates between a conversation and its reply, and the messages LangChain4j sends and receives.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LangChainMessages
{
    private LangChainMessages()
    {
        // Utility
    }

    /**
     * The conversation as LangChain4j messages, the system prompt first when there is one.
     *
     * @param systemPrompt the system prompt, or {@code null} or blank for none
     * @param messages the conversation turns; a role other than assistant or system is a user turn
     * @return the messages, in order
     */
    static List<ChatMessage> buildMessages(final String systemPrompt, final List<LLMMessage> messages)
    {
        final Stream<ChatMessage> system = StringUtils.isBlank(systemPrompt) ? Stream.empty()
            : Stream.of(SystemMessage.from(systemPrompt));
        return Stream.concat(system, messages.stream().map(LangChainMessages::buildTurn))
            .collect(Collectors.toList());
    }

    /**
     * The reply, with the model asked for and what the provider said answered.
     *
     * @param model the model asked for, as configured
     * @param response what the provider answered
     * @return the reply
     * @throws IOException if the provider answered with no text
     */
    static LLMReply readReply(final String model, final ChatResponse response) throws IOException
    {
        final String text = response.aiMessage().text();
        if (text == null) {
            // LangChain4j leaves the text null when the provider answered with empty content, which a reasoning
            // model that spends its whole budget before saying anything visible does
            throw new IOException("The LLM returned an empty answer; the model may have reached its "
                + "output-token limit before producing any visible content");
        }
        // Only an OpenAI-compatible reply carries a fingerprint
        final String fingerprint = response.metadata() instanceof final OpenAiChatResponseMetadata openAi
            ? openAi.systemFingerprint() : null;
        return new LLMReply(text, model, response.modelName(), fingerprint);
    }

    private static ChatMessage buildTurn(final LLMMessage message)
    {
        final String role = message.getRole();
        if ("assistant".equalsIgnoreCase(role)) {
            return AiMessage.from(message.getContent());
        }
        if ("system".equalsIgnoreCase(role)) {
            return SystemMessage.from(message.getContent());
        }
        return UserMessage.from(message.getContent());
    }
}
