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

import java.security.SecureRandom;
import java.util.Base64;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * The key that signs session cookies, kept in the repository so that every instance sharing it accepts the same
 * cookies. Repoinit creates its node and lets only this module's service user read it. The first instance to need the
 * key creates it. When two do so at once, the second commit fails, and that instance uses the key the first stored.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SessionKeyStore
{
    /** The node holding the key. */
    static final String KEY_PATH = "/jcr:system/iap-sessions/SigningKey";

    /** The property of {@link #KEY_PATH} holding the key, in Base64. */
    static final String KEY_PROPERTY = "key";

    private static final int KEY_BYTES = 32;

    private final ResourceResolver resolver;

    private final SecureRandom random;

    /**
     * Works on the key node through a resolver.
     *
     * @param resolver the service user's resolver, which may read the key node and set its properties
     * @param random the source of a new key
     */
    SessionKeyStore(final ResourceResolver resolver, final SecureRandom random)
    {
        this.resolver = resolver;
        this.random = random;
    }

    /**
     * The stored key, or a new one, stored first.
     *
     * @return the key
     * @throws IllegalStateException if the key node cannot be read, or a new key cannot be set on it, which means the
     *     instance is deployed wrong
     * @throws PersistenceException if no key is stored and a new one cannot be
     */
    byte[] readOrCreate() throws PersistenceException
    {
        final String stored = keyNode().getValueMap().get(KEY_PROPERTY, String.class);
        return stored == null ? create() : Base64.getDecoder().decode(stored);
    }

    private byte[] create() throws PersistenceException
    {
        final byte[] key = new byte[KEY_BYTES];
        this.random.nextBytes(key);
        final ModifiableValueMap properties = keyNode().adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new IllegalStateException("The session key node " + KEY_PATH + " cannot be written to");
        }
        properties.put(KEY_PROPERTY, Base64.getEncoder().encodeToString(key));
        try {
            this.resolver.commit();
            return key;
        } catch (final PersistenceException e) {
            this.resolver.revert();
            this.resolver.refresh();
            final String winner = keyNode().getValueMap().get(KEY_PROPERTY, String.class);
            if (winner == null) {
                throw e;
            }
            return Base64.getDecoder().decode(winner);
        }
    }

    private Resource keyNode()
    {
        final Resource node = this.resolver.getResource(KEY_PATH);
        if (node == null) {
            throw new IllegalStateException("The session key node " + KEY_PATH + " cannot be read");
        }
        return node;
    }
}
