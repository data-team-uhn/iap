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
package io.uhndata.iap.utils.internal;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.utils.PrefixTree;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PrefixTreeResourceProvider}, over two trees.
 *
 * @version $Id$
 */
class PrefixTreeResourceProviderTest
{
    /** A UUID, which is long enough to file. */
    private static final String NAME = "3fa91c48-0000-4000-8000-000000000000";

    /** Where each provider is mounted. */
    private static final String ARCHIVE_MOUNT = "/Archive/" + PrefixTree.ADDRESS_SEGMENT;

    private static final String SUBMISSIONS_MOUNT = "/Submissions/" + PrefixTree.ADDRESS_SEGMENT;

    private ResolveContext<Object> context;

    private ResolveContext<Object> belowContext;

    private ResourceProvider<Object> repository;

    private ResourceContext resourceContext;

    private Resource stored;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        this.context = mock(ResolveContext.class);
        this.belowContext = mock(ResolveContext.class);
        this.repository = mock(ResourceProvider.class);
        this.resourceContext = mock(ResourceContext.class);
        this.stored = mock(Resource.class);
        // thenReturn cannot take a concrete value for a getter declared with a wildcard
        doReturn(this.repository).when(this.context).getParentResourceProvider();
        doReturn(this.belowContext).when(this.context).getParentResolveContext();
    }

    /** A provider mounted at a tree root, the way a factory configuration mounts one. */
    private PrefixTreeResourceProvider mountedAt(final String root)
    {
        final PrefixTreeResourceProvider provider = new PrefixTreeResourceProvider();
        provider.activate(Map.of(ResourceProvider.PROPERTY_ROOT, root));
        return provider;
    }

    private Resource resolve(final PrefixTreeResourceProvider provider, final String path)
    {
        return provider.getResource(this.context, path, this.resourceContext, null);
    }

    @Test
    void aShortPathFindsTheNodeInItsBucket()
    {
        when(this.repository.getResource(any(), eq("/Archive/3f/a9/1c/" + NAME), any(), any()))
            .thenReturn(this.stored);

        assertSame(this.stored, this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void theSameComponentServesAnyTreeItIsMountedAt()
    {
        when(this.repository.getResource(any(), eq("/Submissions/3f/a9/1c/" + NAME), any(), any()))
            .thenReturn(this.stored);

        assertSame(this.stored, this.resolve(this.mountedAt(SUBMISSIONS_MOUNT), SUBMISSIONS_MOUNT + "/" + NAME));
    }

    @Test
    void theRealTreeIsLeftEntirelyToTheRepository()
    {
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        final PrefixTreeResourceProvider provider = this.mountedAt(ARCHIVE_MOUNT);

        assertNull(this.resolve(provider, "/Archive"));
        assertNull(this.resolve(provider, "/Archive/3f"));
        assertNull(this.resolve(provider, "/Archive/3f/a9/1c/" + NAME));
        // Not even passed on to the provider below
        verify(this.repository, never()).getResource(any(), any(), any(), any());
    }

    @Test
    void aTreeOnlyAnswersForItsOwnRoot()
    {
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        assertNull(this.resolve(this.mountedAt(SUBMISSIONS_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void theBucketPathIsTheOnePrefixTreeComputes()
    {
        when(this.repository.getResource(any(), eq(PrefixTree.pathFor("/Archive", NAME)), any(), any()))
            .thenReturn(this.stored);
        assertSame(this.stored, this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void aShortPathForSomethingThatIsNotThereStaysNotThere()
    {
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        assertNull(this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void aDeeperPathIsLeftToTheRepository()
    {
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        assertNull(this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME + "/0/victim"));
    }

    @Test
    void aPathWithAnExtensionIsDeclined()
    {
        // Sling tries `<root>/<name>.entry.json` before `<root>/<name>`
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        final PrefixTreeResourceProvider provider = this.mountedAt(ARCHIVE_MOUNT);
        assertNull(this.resolve(provider, ARCHIVE_MOUNT + "/" + NAME + ".entry.json"));
        assertNull(this.resolve(provider, ARCHIVE_MOUNT + "/" + NAME + "/.html"));
    }

    @Test
    void aProviderWithNoRootAnswersNothing()
    {
        when(this.repository.getResource(any(), any(), any(), any())).thenReturn(null);
        final PrefixTreeResourceProvider provider = new PrefixTreeResourceProvider();
        provider.activate(Map.of());
        assertNull(this.resolve(provider, ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void nothingBelowMeansNothingToAnswerWith()
    {
        doReturn(null).when(this.context).getParentResourceProvider();
        assertNull(this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }

    @Test
    void aParentWithoutAContextIsAlsoRefused()
    {
        doReturn(null).when(this.context).getParentResolveContext();
        assertNull(this.resolve(this.mountedAt(ARCHIVE_MOUNT), ARCHIVE_MOUNT + "/" + NAME));
    }
}
