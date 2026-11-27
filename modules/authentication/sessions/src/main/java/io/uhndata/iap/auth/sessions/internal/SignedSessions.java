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
package io.uhndata.iap.auth.sessions.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.uhndata.iap.auth.sessions.api.Session;

/**
 * Turns a session into a cookie value and back. The value is the session's four fields, each in Base64 so that a user
 * name can hold any character, then an HMAC-SHA256 of them. A value whose signature does not match was not made with
 * this key, and is not a session.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SignedSessions
{
    private static final String SIGNATURE_ALGORITHM = "HmacSHA256";

    private static final String FIELD_SEPARATOR = ".";

    private static final int FIELD_COUNT = 4;

    private static final Base64.Encoder FIELD_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder FIELD_DECODER = Base64.getUrlDecoder();

    private final Mac mac;

    /**
     * Signs and checks with a key.
     *
     * @param key the signing key
     * @throws GeneralSecurityException if the platform cannot compute the signature, or the key does not suit it
     */
    SignedSessions(final byte[] key) throws GeneralSecurityException
    {
        this.mac = Mac.getInstance(SIGNATURE_ALGORITHM);
        this.mac.init(new SecretKeySpec(key, SIGNATURE_ALGORITHM));
    }

    /**
     * The cookie value holding a session.
     *
     * @param session the session
     * @return its signed value
     */
    String encode(final Session session)
    {
        final String payload = Stream.of(session.userId(), session.kind(),
            Long.toString(session.signedIn().toEpochMilli()), Long.toString(session.idleDeadline().toEpochMilli()))
            .map(field -> FIELD_ENCODER.encodeToString(field.getBytes(StandardCharsets.UTF_8)))
            .collect(Collectors.joining(FIELD_SEPARATOR));
        return payload + FIELD_SEPARATOR + FIELD_ENCODER.encodeToString(sign(payload));
    }

    /**
     * The session a cookie value holds.
     *
     * @param value a cookie value
     * @return the session; {@code null} if the value is malformed or not signed with this key
     */
    Session decode(final String value)
    {
        final String[] parts = value.split("\\.", -1);
        if (parts.length != FIELD_COUNT + 1) {
            return null;
        }
        final String payload = String.join(FIELD_SEPARATOR, Arrays.copyOf(parts, FIELD_COUNT));
        try {
            if (!MessageDigest.isEqual(sign(payload), FIELD_DECODER.decode(parts[FIELD_COUNT]))) {
                return null;
            }
            return new Session(text(parts[0]), text(parts[1]), Instant.ofEpochMilli(Long.parseLong(text(parts[2]))),
                Instant.ofEpochMilli(Long.parseLong(text(parts[3]))));
        } catch (final IllegalArgumentException e) {
            // Not Base64, or a time that is not a number: not a value this platform made
            return null;
        }
    }

    private synchronized byte[] sign(final String payload)
    {
        return this.mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(final String field)
    {
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(FIELD_DECODER.decode(field))).toString();
    }
}
