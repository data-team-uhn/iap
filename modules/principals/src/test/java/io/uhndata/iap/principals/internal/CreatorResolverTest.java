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
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CreatorResolver}: the recorded human first, the repository's answer second, nobody for a
 * resource nothing raised.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreatorResolverTest
{
    private final SlingContext context = new SlingContext();

    private final CreatorResolver resolver = new CreatorResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class);
    }

    @Test
    void answersForItsName()
    {
        assertEquals(PrincipalService.CREATOR, this.resolver.getName());
    }

    // The engine writes everything as its own service user, so jcr:createdBy names the machinery; the human the
    // engine recorded is the answer
    @Test
    void prefersTheRecordedHumanOverTheRepositoryWriter()
    {
        final Resource subject = this.context.create().resource("/content/one",
            "sling:resourceType", Content.RESOURCE_TYPE,
            "createdBy", "alice",
            "jcr:createdBy", "workflows");
        assertEquals(List.of("alice"), this.resolver.resolve(PrincipalContext.about(subject)));
    }

    // Content somebody wrote directly has no separate record, and the repository's own answer is the right one
    @Test
    void fallsBackToTheRepositoryWriterOnContent()
    {
        final Resource subject = this.context.create().resource("/content/two",
            "sling:resourceType", Content.RESOURCE_TYPE,
            "jcr:createdBy", "bob");
        assertEquals(List.of("bob"), this.resolver.resolve(PrincipalContext.about(subject)));
    }

    // A resource that is not content at all may still carry an explicit record
    @Test
    void readsAPlainResourcesOwnRecord()
    {
        final Resource subject = this.context.create().resource("/elsewhere/three", "createdBy", "carol");
        assertEquals(List.of("carol"), this.resolver.resolve(PrincipalContext.about(subject)));
    }

    @Test
    void standsForNobodyOnAResourceNothingRaised()
    {
        assertTrue(this.resolver.resolve(PrincipalContext.about(this.context.create().resource("/bare")))
            .isEmpty());
        assertTrue(this.resolver.resolve(
            PrincipalContext.about(this.context.create().resource("/blank", "createdBy", " "))).isEmpty());
    }

    @Test
    void standsForNobodyWithNoSubjectAtAll()
    {
        assertTrue(this.resolver.resolve(PrincipalContext.actedBy("alice")).isEmpty());
    }

    // In a context with no model registry at all, adaptation itself answers nothing
    @Test
    void readsTheRecordWhenTheModelCannotAdapt()
    {
        final Resource subject = Mockito.mock(Resource.class);
        Mockito.when(subject.adaptTo(Content.class)).thenReturn(null);
        Mockito.when(subject.getValueMap())
            .thenReturn(new ValueMapDecorator(Map.of("createdBy", "dave")));
        assertEquals(List.of("dave"), this.resolver.resolve(PrincipalContext.about(subject)));
    }
}
