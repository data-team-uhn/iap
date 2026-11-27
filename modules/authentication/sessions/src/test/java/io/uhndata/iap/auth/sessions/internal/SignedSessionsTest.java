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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.auth.sessions.api.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link SignedSessions}.
 *
 * @version $Id$
 */
class SignedSessionsTest
{
    private static final Session SESSION = new Session("anne.o'neil|ü.x", "oidc:keycloak",
        Instant.parse("2026-11-26T09:00:00Z"), Instant.parse("2026-11-26T09:30:00Z"));

    private static SignedSessions signer(final String key) throws GeneralSecurityException
    {
        return new SignedSessions(key.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsBackWhatItSigned() throws GeneralSecurityException
    {
        final SignedSessions signer = signer("the key");

        assertEquals(SESSION, signer.decode(signer.encode(SESSION)));
    }

    @Test
    void refusesAValueSignedWithAnotherKey() throws GeneralSecurityException
    {
        assertNull(signer("the key").decode(signer("another key").encode(SESSION)));
    }

    @Test
    void refusesAValueWhoseFieldsWereChanged() throws GeneralSecurityException
    {
        final SignedSessions signer = signer("the key");
        final String value = signer.encode(SESSION);
        final String later = signer.encode(new Session(SESSION.userId(), SESSION.kind(), SESSION.signedIn(),
            Instant.parse("2026-11-26T17:00:00Z")));

        // The later deadline with the earlier signature
        final String forged = later.substring(0, later.lastIndexOf('.')) + value.substring(value.lastIndexOf('.'));

        assertNull(signer.decode(forged));
    }

    @Test
    void refusesAValueThatIsNotOneOfItsOwn() throws GeneralSecurityException
    {
        final SignedSessions signer = signer("the key");

        assertNull(signer.decode(""));
        assertNull(signer.decode("a.b.c"));
        assertNull(signer.decode("a.b.c.d.e.f"));
        assertNull(signer.decode("a.b.c.d.not base64!"));
    }
}
