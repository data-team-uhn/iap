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

import java.util.List;

import javax.jcr.Session;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlets.post.Modification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link DenyScriptsSlingPostProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DenyScriptsSlingPostProcessorTest
{
    private static final String PATH = "/content/upload";

    private DenyScriptsSlingPostProcessor processor;

    private SlingJakartaHttpServletRequest request;

    private ResourceResolver resolver;

    private Session session;

    @BeforeEach
    public void setup()
    {
        this.processor = new DenyScriptsSlingPostProcessor();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.resolver = Mockito.mock(ResourceResolver.class);
        this.session = Mockito.mock(Session.class);
        Mockito.when(this.request.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
    }

    @Test
    public void testAdminIsAllowedToUploadScripts()
        throws Exception
    {
        // Who the repository says is asking, not the spelling they typed: a login resolves case-insensitively,
        // and the exemption belongs to the account rather than to a capitalisation of its name
        Mockito.when(this.session.getUserID()).thenReturn("admin");

        // Even with a script modification present, the admin bypasses the check without inspecting the upload
        this.processor.process(this.request, List.of(mockModification()));

        Mockito.verify(this.resolver, Mockito.never()).getResource(Mockito.anyString());
    }

    @Test
    public void testScriptContentTypeIsRejected()
    {
        mockResourceWithContentType("text/javascript");

        final Exception e = Assertions.assertThrows(Exception.class,
            () -> this.processor.process(this.request, List.of(mockModification())));
        Assertions.assertEquals("Script files are not allowed", e.getMessage());
    }

    @Test
    public void testHtmlContentTypeIsRejected()
    {
        mockResourceWithContentType("text/html");

        final Exception e = Assertions.assertThrows(Exception.class,
            () -> this.processor.process(this.request, List.of(mockModification())));
        Assertions.assertEquals("HTML files are not allowed", e.getMessage());
    }

    @Test
    public void testAllowedContentTypePasses()
        throws Exception
    {
        mockResourceWithContentType("image/png");

        Assertions.assertDoesNotThrow(() -> this.processor.process(this.request, List.of(mockModification())));
    }

    @Test
    public void testMissingResourceIsSkipped()
        throws Exception
    {
        Mockito.when(this.session.getUserID()).thenReturn("user");
        Mockito.when(this.resolver.getResource(PATH)).thenReturn(null);

        Assertions.assertDoesNotThrow(() -> this.processor.process(this.request, List.of(mockModification())));
    }

    @Test
    public void testNullContentTypeIsSkipped()
    {
        mockResourceWithContentType(null);

        Assertions.assertDoesNotThrow(() -> this.processor.process(this.request, List.of(mockModification())));
    }

    private Modification mockModification()
    {
        final Modification modification = Mockito.mock(Modification.class);
        Mockito.when(modification.getSource()).thenReturn(PATH);
        return modification;
    }

    private void mockResourceWithContentType(final String contentType)
    {
        Mockito.when(this.session.getUserID()).thenReturn("user");
        final Resource resource = Mockito.mock(Resource.class);
        final ResourceMetadata metadata = new ResourceMetadata();
        if (contentType != null) {
            metadata.setContentType(contentType);
        }
        Mockito.when(resource.getResourceMetadata()).thenReturn(metadata);
        Mockito.when(this.resolver.getResource(PATH)).thenReturn(resource);
    }
}
