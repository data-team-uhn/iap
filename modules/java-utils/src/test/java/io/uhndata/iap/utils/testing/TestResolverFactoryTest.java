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
package io.uhndata.iap.utils.testing;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TestResolverFactory}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TestResolverFactoryTest
{
    private static final String SUBSERVICE = "link-types";

    private static final Map<String, Object> SET_APART = Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

    private static final Map<String, Object> ANY_OTHER = Map.of(ResourceResolverFactory.SUBSERVICE, "links");

    private ResourceResolver testResolver;

    private ResourceResolver otherResolver;

    @BeforeEach
    void setUp()
    {
        this.testResolver = resolverFinding("/test");
        this.otherResolver = resolverFinding("/other");
    }

    @Test
    void servesTheGivenResolverToEveryServiceUser() throws LoginException
    {
        final TestResolverFactory factory = TestResolverFactory.serving(this.testResolver);

        assertFinds("/test", factory.getServiceResourceResolver(ANY_OTHER));
        assertFinds("/test", factory.getServiceResourceResolver(null));
    }

    @Test
    void closingAServedResolverLeavesTheTestsOpen() throws LoginException
    {
        TestResolverFactory.serving(this.testResolver).getServiceResourceResolver(ANY_OTHER).close();

        Mockito.verify(this.testResolver, Mockito.never()).close();
    }

    @Test
    void refusesEveryServiceUserWhenThereAreNone()
    {
        final TestResolverFactory factory = TestResolverFactory.withoutServiceUser();

        assertThrows(LoginException.class, () -> factory.getServiceResourceResolver(ANY_OTHER));
        assertThrows(LoginException.class, () -> factory.getServiceResourceResolver(null));
    }

    @Test
    void answersEveryLoginThroughTheGivenFunction() throws LoginException
    {
        final AtomicReference<Map<String, Object>> asked = new AtomicReference<>();
        final TestResolverFactory factory = TestResolverFactory.answeredBy(authenticationInfo -> {
            asked.set(authenticationInfo);
            return this.otherResolver;
        });

        assertSame(this.otherResolver, factory.getServiceResourceResolver(ANY_OTHER));
        assertSame(ANY_OTHER, asked.get());
    }

    @Test
    void servesASubserviceSetApartItsOwnResolver() throws LoginException
    {
        final TestResolverFactory factory = TestResolverFactory.serving(this.otherResolver)
            .withSubservice(SUBSERVICE, this.testResolver);

        assertFinds("/test", factory.getServiceResourceResolver(SET_APART));
        assertFinds("/other", factory.getServiceResourceResolver(ANY_OTHER));
        assertFinds("/other", factory.getServiceResourceResolver(null));
    }

    @Test
    void closingASubservicesResolverLeavesTheTestsOpen() throws LoginException
    {
        TestResolverFactory.withoutServiceUser().withSubservice(SUBSERVICE, this.testResolver)
            .getServiceResourceResolver(SET_APART).close();

        Mockito.verify(this.testResolver, Mockito.never()).close();
    }

    @Test
    void refusesOnlyTheSubserviceTakenAway() throws LoginException
    {
        final TestResolverFactory factory = TestResolverFactory.serving(this.otherResolver)
            .withoutSubservice(SUBSERVICE);

        assertThrows(LoginException.class, () -> factory.getServiceResourceResolver(SET_APART));
        assertFinds("/other", factory.getServiceResourceResolver(ANY_OTHER));
    }

    @Test
    void settingASubserviceApartLeavesTheOriginalFactoryAlone() throws LoginException
    {
        final TestResolverFactory original = TestResolverFactory.serving(this.otherResolver);
        original.withoutSubservice(SUBSERVICE);

        assertFinds("/other", original.getServiceResourceResolver(SET_APART));
    }

    @Test
    @SuppressWarnings("deprecation")
    void supportsServiceLoginsOnly()
    {
        final TestResolverFactory factory = TestResolverFactory.serving(this.testResolver);

        assertThrows(UnsupportedOperationException.class, () -> factory.getResourceResolver(null));
        assertThrows(UnsupportedOperationException.class, () -> factory.getAdministrativeResourceResolver(null));
        assertThrows(UnsupportedOperationException.class, factory::getThreadResourceResolver);
        assertThrows(UnsupportedOperationException.class, factory::getSearchPath);
    }

    private static ResourceResolver resolverFinding(final String path)
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.getResource(path)).thenReturn(Mockito.mock(Resource.class));
        return resolver;
    }

    private static void assertFinds(final String path, final ResourceResolver served)
    {
        assertNotNull(served.getResource(path), "The served resolver does not find " + path);
    }
}
