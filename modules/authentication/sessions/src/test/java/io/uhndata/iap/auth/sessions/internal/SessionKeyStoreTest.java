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
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ModifiableValueMapDecorator;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionKeyStore}.
 *
 * @version $Id$
 */
class SessionKeyStoreTest
{
    private static final byte[] STORED = { 1, 2, 3 };

    private final ResourceResolver resolver = mock(ResourceResolver.class);

    private final Map<String, Object> properties = new HashMap<>();

    private final SessionKeyStore store = new SessionKeyStore(this.resolver, new SecureRandom());

    // The key node as the resolver shows it, its properties read from and written to the map above
    private void keyNode()
    {
        final Resource node = mock(Resource.class);
        when(node.getValueMap()).thenAnswer(invocation -> new ValueMapDecorator(this.properties));
        when(node.adaptTo(ModifiableValueMap.class))
            .thenAnswer(invocation -> new ModifiableValueMapDecorator(this.properties));
        when(this.resolver.getResource(SessionKeyStore.KEY_PATH)).thenReturn(node);
    }

    @Test
    void readsTheStoredKey() throws PersistenceException
    {
        this.keyNode();
        this.properties.put(SessionKeyStore.KEY_PROPERTY, Base64.getEncoder().encodeToString(STORED));

        assertArrayEquals(STORED, this.store.readOrCreate());
        verify(this.resolver, never()).commit();
    }

    @Test
    void storesANewKeyWhenThereIsNone() throws PersistenceException
    {
        this.keyNode();

        final byte[] key = this.store.readOrCreate();

        assertEquals(32, key.length);
        assertArrayEquals(key,
            Base64.getDecoder().decode((String) this.properties.get(SessionKeyStore.KEY_PROPERTY)));
        verify(this.resolver).commit();
    }

    @Test
    void takesTheKeyAnotherInstanceStoredFirst() throws PersistenceException
    {
        this.keyNode();
        doThrow(new PersistenceException("conflict")).when(this.resolver).commit();
        // Discarding the losing write, then reading what the other instance committed
        doAnswer(invocation -> this.properties.remove(SessionKeyStore.KEY_PROPERTY)).when(this.resolver).revert();
        doAnswer(invocation -> this.properties.put(SessionKeyStore.KEY_PROPERTY,
            Base64.getEncoder().encodeToString(STORED))).when(this.resolver).refresh();

        assertArrayEquals(STORED, this.store.readOrCreate());
    }

    @Test
    void failsWhenNoKeyCanBeStored() throws PersistenceException
    {
        this.keyNode();
        final PersistenceException failure = new PersistenceException("the repository is read-only");
        doThrow(failure).when(this.resolver).commit();
        doAnswer(invocation -> this.properties.remove(SessionKeyStore.KEY_PROPERTY)).when(this.resolver).revert();

        assertSame(failure, assertThrows(PersistenceException.class, this.store::readOrCreate));
    }

    @Test
    void failsWithoutTheKeyNode()
    {
        assertThrows(IllegalStateException.class, this.store::readOrCreate);
    }

    @Test
    void failsWhenTheKeyNodeCannotBeWritten()
    {
        final Resource node = mock(Resource.class);
        when(node.getValueMap()).thenReturn(ValueMap.EMPTY);
        when(this.resolver.getResource(SessionKeyStore.KEY_PATH)).thenReturn(node);

        assertThrows(IllegalStateException.class, this.store::readOrCreate);
    }
}
