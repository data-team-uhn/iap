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
package io.uhndata.iap.workflows.internal;

import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link EngineSessions}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class EngineSessionsTest
{
    @Test
    void opensTheEnginesServiceUser() throws Exception
    {
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        final ResourceResolver session = Mockito.mock(ResourceResolver.class);
        Mockito.when(factory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "workflows")))
            .thenReturn(session);

        assertSame(session, new EngineSessions(factory).open());
    }

    @Test
    void failsAsTheEngineWhenItsServiceUserIsMissing() throws Exception
    {
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));

        assertThrows(WorkflowFailedException.class, new EngineSessions(factory)::open);
    }
}
