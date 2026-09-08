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
package io.uhndata.iap.principals.internal;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MeResolver}: the actor when there is one, nobody when there is not.
 *
 * @version $Id$
 * @since 0.1.0
 */
class MeResolverTest
{
    private final MeResolver resolver = new MeResolver();

    @Test
    void answersForItsName()
    {
        assertEquals(PrincipalService.ME, this.resolver.getName());
    }

    @Test
    void standsForTheActor()
    {
        assertEquals(List.of("alice"), this.resolver.resolve(PrincipalContext.actedBy("alice")));
    }

    // A timer or a scheduled sweep acts for nobody, and the honest answer is nobody rather than the service user
    // the machinery happens to run as
    @Test
    void standsForNobodyWhenNobodyIsActing()
    {
        assertTrue(this.resolver.resolve(PrincipalContext.actedBy(null)).isEmpty());
        assertTrue(this.resolver.resolve(PrincipalContext.actedBy(" ")).isEmpty());
    }
}
