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
package io.uhndata.iap.auth.token.jwt.impl;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Decides what may serve as an instance's identity in the {@code iss} and {@code aud} claims. The same rule checks
 * this instance's configured identity and each peer's registered issuer.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JwtIssuers
{
    private static final int MAX_LENGTH = 2048;

    /** A control character, such as a line break. */
    private static final Pattern CONTROL_CHARACTER_PATTERN = Pattern.compile("\\p{Cntrl}");

    private JwtIssuers()
    {
        // Utility class, not to be instantiated
    }

    /**
     * Whether a value can serve as an identity. A token's claim is compared with it exactly, with no normalization,
     * so surrounding whitespace is refused rather than trimmed. The value must not be blank, be longer than 2048
     * characters, or hold a control character. A value with a colon must be an absolute URI, as RFC 7519 requires
     * of a StringOrURI.
     *
     * @param issuer the candidate identity, or {@code null}
     * @return {@code true} if it is usable, {@code false} if it is {@code null} or breaks a rule above
     */
    static boolean isUsable(final String issuer)
    {
        if (issuer == null) {
            return false;
        }
        if (issuer.isBlank() || issuer.length() > MAX_LENGTH || !issuer.equals(issuer.strip())
            || CONTROL_CHARACTER_PATTERN.matcher(issuer).find()) {
            return false;
        }
        if (issuer.indexOf(':') < 0) {
            return true;
        }
        try {
            return URI.create(issuer).isAbsolute();
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }
}
