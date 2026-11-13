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

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

/**
 * Sends one message to the model in a way the submitter can stop. Every model call of a reading goes through here.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ModelCall
{
    private ModelCall()
    {
        // Utility
    }

    /**
     * Sends one message to the model, unless this reading was stopped. A stop throws {@link ReadingStoppedException},
     * before or after the call.
     *
     * @param client the client to ask through
     * @param system the system prompt
     * @param userMessage what to show the model
     * @param options the options for the call, including the schema the reply must match
     * @return the reply
     * @throws IOException if the model cannot be reached
     */
    static String ask(final LLMClient client, final String system, final String userMessage,
        final LLMRequestOptions options) throws IOException
    {
        ReadingRuns.enterModelCall();
        try {
            return client.chat(system, List.of(new LLMMessage("user", userMessage)), options);
        } catch (final IOException e) {
            if (ReadingRuns.isCausedByStop(e)) {
                throw new ReadingStoppedException();
            }
            throw e;
        } finally {
            // Whatever the call threw, or a stop during a call whose client does not abort on an interrupt, which
            // rejects the reply here
            ReadingRuns.leaveModelCall();
        }
    }
}
