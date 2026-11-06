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
package io.uhndata.iap.adminconsole.internal;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link AdminViewResourceProvider}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AdminViewResourceProviderTest
{
    private final SlingContext slingContext = new SlingContext();

    private AdminViewResourceProvider provider;

    private ResolveContext<Object> context;

    private ResolveContext<Object> parentContext;

    private ResourceProvider<Object> repository;

    private ResourceResolver resolver;

    private ResourceContext resourceContext;

    private Resource stored;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        this.provider = new AdminViewResourceProvider();
        this.resolver = this.slingContext.resourceResolver();
        this.context = Mockito.mock(ResolveContext.class);
        this.parentContext = Mockito.mock(ResolveContext.class);
        this.repository = Mockito.mock(ResourceProvider.class);
        this.resourceContext = Mockito.mock(ResourceContext.class);
        this.stored = Mockito.mock(Resource.class);
    }

    /** The repository below holds nothing but the console node, which the caller can read. */
    private void withNothingStored()
    {
        Mockito.doReturn(this.repository).when(this.context).getParentResourceProvider();
        Mockito.doReturn(this.parentContext).when(this.context).getParentResolveContext();
        Mockito.when(this.repository.getResource(ArgumentMatchers.any(), ArgumentMatchers.any(),
            ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(null);
        Mockito.when(this.repository.getResource(ArgumentMatchers.any(),
            ArgumentMatchers.eq(AdminViewResourceProvider.ADMIN_ROOT), ArgumentMatchers.any(),
            ArgumentMatchers.any())).thenReturn(this.stored);
    }

    @Test
    void synthesizesTheConsoleForPathsBelowIt()
    {
        withNothingStored();
        Mockito.when(this.context.getResourceResolver()).thenReturn(this.resolver);

        for (String path : new String[] { "/admin/users", "/admin/groups/reviewers", "/admin/a/b/c/d" }) {
            final Resource result = this.provider.getResource(this.context, path, this.resourceContext, null);

            assertEquals(path, result.getPath());
            assertEquals(AdminViewResourceProvider.VIEW_RESOURCE_TYPE, result.getResourceType());
            assertSame(this.resolver, result.getResourceResolver());
        }
    }

    @Test
    void declinesThePathsResolutionIsStillPeelingAnExtensionOff()
    {
        withNothingStored();

        for (String path : new String[] { "/admin/users.html", "/admin/users/.html",
            "/admin/groups/reviewers.html", "/admin/users.print.html" }) {
            assertNull(this.provider.getResource(this.context, path, this.resourceContext, null));
        }
    }

    @Test
    void stillSynthesizesAViewWhoseAncestorCarriesADot()
    {
        withNothingStored();
        Mockito.when(this.context.getResourceResolver()).thenReturn(this.resolver);

        assertEquals("/admin/a.b/users",
            this.provider.getResource(this.context, "/admin/a.b/users", this.resourceContext, null).getPath());
    }

    @Test
    void leavesTheConsoleItselfToTheRepository()
    {
        withNothingStored();

        assertSame(this.stored, this.provider.getResource(this.context, "/admin", this.resourceContext, null));
    }

    @Test
    void synthesizesNothingWhenTheConsoleCannotBeRead()
    {
        Mockito.doReturn(this.repository).when(this.context).getParentResourceProvider();
        Mockito.doReturn(this.parentContext).when(this.context).getParentResolveContext();
        // Nothing is readable, the console included
        Mockito.when(this.repository.getResource(ArgumentMatchers.any(), ArgumentMatchers.any(),
            ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(null);

        assertNull(this.provider.getResource(this.context, "/admin/users", this.resourceContext, null));
    }

    @Test
    void ignoresPathsThatMerelyStartWithTheSameLetters()
    {
        withNothingStored();

        for (String path : new String[] { "/adminx", "/adminx/tools", "/administration" }) {
            assertNull(this.provider.getResource(this.context, path, this.resourceContext, null));
        }
    }

    @Test
    void letsStoredContentWin()
    {
        Mockito.doReturn(this.repository).when(this.context).getParentResourceProvider();
        Mockito.doReturn(this.parentContext).when(this.context).getParentResolveContext();
        Mockito.when(this.repository.getResource(ArgumentMatchers.eq(this.parentContext),
            ArgumentMatchers.eq("/admin/categories"), ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(this.stored);

        final Resource result =
            this.provider.getResource(this.context, "/admin/categories", this.resourceContext, null);

        assertSame(this.stored, result);
        assertFalse(result instanceof SyntheticResource);
    }

    @Test
    void passesTheResolutionOnUnchanged()
    {
        Mockito.doReturn(this.repository).when(this.context).getParentResourceProvider();
        Mockito.doReturn(this.parentContext).when(this.context).getParentResolveContext();
        Mockito.when(this.repository.getResource(ArgumentMatchers.eq(this.parentContext),
            ArgumentMatchers.eq("/admin/users"), ArgumentMatchers.eq(this.resourceContext),
            ArgumentMatchers.eq(this.stored))).thenReturn(this.stored);

        assertSame(this.stored,
            this.provider.getResource(this.context, "/admin/users", this.resourceContext, this.stored));
    }

    @Test
    void synthesizesNothingWithoutARepositoryToVouchForTheConsole()
    {
        Mockito.doReturn(null).when(this.context).getParentResourceProvider();

        assertNull(this.provider.getResource(this.context, "/admin/users", this.resourceContext, null));
    }

    @Test
    void synthesizesNothingWhenTheRepositoryBelowHasNoContextToAskIn()
    {
        Mockito.doReturn(this.repository).when(this.context).getParentResourceProvider();
        Mockito.doReturn(null).when(this.context).getParentResolveContext();

        assertNull(this.provider.getResource(this.context, "/admin/users", this.resourceContext, null));
        Mockito.verifyNoInteractions(this.repository);
    }

    @Test
    void doesNotEnumerateWhatOnlyExistsWhenAskedFor()
    {
        assertNull(this.provider.listChildren(this.context, this.stored));
    }
}
