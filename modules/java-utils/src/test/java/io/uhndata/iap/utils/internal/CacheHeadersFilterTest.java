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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link CacheHeadersFilter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CacheHeadersFilterTest
{
    private static final String CACHE_CONTROL = "Cache-Control";

    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private static final String ALWAYS_REVALIDATE = "no-cache";

    private CacheHeadersFilter filter;

    private SlingJakartaHttpServletRequest request;

    private HttpServletResponse response;

    private FilterChain chain;

    @BeforeEach
    void setUp()
    {
        this.filter = new CacheHeadersFilter();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(HttpServletResponse.class);
        this.chain = Mockito.mock(FilterChain.class);
    }

    private void mockResourcePath(final String path)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn(path);
        Mockito.when(this.request.getResource()).thenReturn(resource);
    }

    @Test
    void contentHashedFrontendFilesAreCacheableForever() throws Exception
    {
        mockResourcePath("/libs/iap/resources/runtime.f6d1b7fad82256cf4412.js");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, CACHE_FOREVER);
        Mockito.verify(this.chain).doFilter(this.request, this.response);
    }

    @Test
    void hashedLicenseAndSourceMapFilesAreCacheableForever() throws Exception
    {
        mockResourcePath("/libs/iap/resources/vendor.0f3a9c774b1e2d885a6f.js.LICENSE.txt");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, CACHE_FOREVER);
    }

    @Test
    void theAssetManifestMustAlwaysBeRevalidated() throws Exception
    {
        mockResourcePath("/libs/iap/resources/assets.json");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, ALWAYS_REVALIDATE);
    }

    @Test
    void unhashedFrontendResourcesMustAlwaysBeRevalidated() throws Exception
    {
        mockResourcePath("/libs/iap/resources/media/default/logo-dark.svg");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, ALWAYS_REVALIDATE);
    }

    @Test
    void pagesMustAlwaysBeRevalidated() throws Exception
    {
        mockResourcePath("/admin/categories");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, ALWAYS_REVALIDATE);
    }

    @Test
    void hashedNamesOutsideTheFrontendResourcesAreNotImmutable() throws Exception
    {
        mockResourcePath("/content/some.0123456789abcdef0123.js");
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CACHE_CONTROL, ALWAYS_REVALIDATE);
    }

    @Test
    void nonSlingRequestsPassThroughUntouched() throws Exception
    {
        final ServletRequest plainRequest = Mockito.mock(ServletRequest.class);
        this.filter.doFilter(plainRequest, this.response, this.chain);

        Mockito.verify(this.response, Mockito.never()).setHeader(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(this.chain).doFilter(plainRequest, this.response);
    }

    @Test
    void nonHttpResponsesPassThroughUntouched() throws Exception
    {
        final ServletResponse plainResponse = Mockito.mock(ServletResponse.class);
        this.filter.doFilter(this.request, plainResponse, this.chain);

        Mockito.verify(this.chain).doFilter(this.request, plainResponse);
    }

    @Test
    void lifecycleMethodsAreNoOps() throws Exception
    {
        this.filter.init(null);
        this.filter.destroy();

        Mockito.verifyNoInteractions(this.response);
    }
}
