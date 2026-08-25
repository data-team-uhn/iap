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

/**
 * A single turn in a conversation: a role ({@code "user"} or {@code "assistant"}) and its text content.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class LLMMessage
{
    private final String role;

    private final String content;

    /**
     * Create a conversation turn.
     *
     * @param role who is speaking: {@code "user"}, {@code "assistant"} or {@code "system"}
     * @param content the text of the turn
     */
    public LLMMessage(final String role, final String content)
    {
        this.role = role;
        this.content = content;
    }

    /**
     * Who is speaking.
     *
     * @return the role of this turn
     */
    public String getRole()
    {
        return this.role;
    }

    /**
     * The text of this turn.
     *
     * @return the content
     */
    public String getContent()
    {
        return this.content;
    }
}
