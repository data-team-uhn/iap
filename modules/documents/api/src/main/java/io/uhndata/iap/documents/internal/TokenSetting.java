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
 * Reads a token the document daemon and this module share: the callback JWT, or the daemon's own token.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TokenSetting
{
    private TokenSetting()
    {
        // Utility
    }

    /**
     * Reads a token: the OSGi property when set, the environment value otherwise.
     *
     * @param configuration the component configuration
     * @param property the OSGi property that holds the token
     * @param environmentValue what the token's environment variable holds, may be {@code null} when unset
     * @return the token to use, or an empty string when none is configured
     */
    static String resolve(final Map<String, Object> configuration, final String property,
        final String environmentValue)
    {
        final String configured = String.valueOf(configuration.getOrDefault(property, "")).trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        return environmentValue == null ? "" : environmentValue.trim();
    }
}
