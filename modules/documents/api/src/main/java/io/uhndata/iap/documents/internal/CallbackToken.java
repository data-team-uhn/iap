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
package io.uhndata.iap.documents.internal;

import java.util.Map;

/**
 * Resolves the shared Docling callback JWT used by {@link ParseCallbackServlet} and {@link ParseJobConsumer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CallbackToken
{
    private CallbackToken()
    {
        // Utility
    }

    /**
     * Read the shared callback token: the {@link ParseJob#TOKEN_PROPERTY} OSGi property when set, the environment
     * value otherwise.
     *
     * @param configuration the component configuration
     * @param environmentValue what {@link ParseJob#TOKEN_VARIABLE} holds, may be {@code null} when unset
     * @return the token to use, or an empty string when none is configured
     */
    static String resolve(final Map<String, Object> configuration, final String environmentValue)
    {
        final String configured =
            String.valueOf(configuration.getOrDefault(ParseJob.TOKEN_PROPERTY, "")).trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        return environmentValue == null ? "" : environmentValue.trim();
    }
}
