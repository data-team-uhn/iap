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
package io.uhndata.iap.principals.api;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link PrincipalContext}: each factory fills exactly its half.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PrincipalContextTest
{
    @Test
    void aboutCarriesTheSubjectAndNobodyActing()
    {
        final Resource subject = Mockito.mock(Resource.class);
        final PrincipalContext context = PrincipalContext.about(subject);
        assertSame(subject, context.subject());
        assertNull(context.actingUser());
    }

    @Test
    void actedByCarriesTheActorAndNoSubject()
    {
        final PrincipalContext context = PrincipalContext.actedBy("alice");
        assertNull(context.subject());
        assertEquals("alice", context.actingUser());
    }

    @Test
    void theFullConstructorCarriesBoth()
    {
        final Resource subject = Mockito.mock(Resource.class);
        final PrincipalContext context = new PrincipalContext(subject, "alice");
        assertSame(subject, context.subject());
        assertEquals("alice", context.actingUser());
    }
}
