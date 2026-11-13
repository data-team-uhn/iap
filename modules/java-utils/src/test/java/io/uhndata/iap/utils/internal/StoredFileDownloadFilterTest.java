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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * Unit tests for {@link StoredFileDownloadFilter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StoredFileDownloadFilterTest
{
    private static final String CONTENT_DISPOSITION = "Content-Disposition";

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String SVG_WITH_SCRIPT =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(document.cookie)</script></svg>";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final StoredFileDownloadFilter filter = new StoredFileDownloadFilter();

    private SlingJakartaHttpServletRequest request;

    private HttpServletResponse response;

    private FilterChain chain;

    @BeforeEach
    void setUp()
    {
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(HttpServletResponse.class);
        this.chain = Mockito.mock(FilterChain.class);
    }

    @Test
    void anUploadedSvgIsServedAsASandboxedDownload() throws Exception
    {
        request(storeFile("/home/users/m/mallory/drawing.svg"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
        Mockito.verify(this.response).setHeader(CONTENT_SECURITY_POLICY, "sandbox");
        Mockito.verify(this.chain).doFilter(this.request, this.response);
    }

    @Test
    void theContentNodeOfAFileIsServedAsADownload() throws Exception
    {
        request(storeFile("/Submissions/s1/document/uploadedFile").getChild("jcr:content"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
    }

    @Test
    void aNodeHoldingDataItselfIsServedAsADownload() throws Exception
    {
        final Node node = node("/home/users/m/mallory/page", "nt:unstructured");
        node.setProperty("jcr:data", SVG_WITH_SCRIPT);
        node.setProperty("jcr:mimeType", "image/svg+xml");
        request(resource("/home/users/m/mallory/page"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
    }

    @Test
    void aSinglePropertyIsServedAsADownload() throws Exception
    {
        storeFile("/home/users/m/mallory/drawing.svg");
        request(resource("/home/users/m/mallory/drawing.svg/jcr:content/jcr:data"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
    }

    @Test
    void aLinkToAnotherFilesContentIsServedAsADownload() throws Exception
    {
        final Resource file = storeFile("/home/users/m/mallory/drawing.svg");
        final Node content = file.getChild("jcr:content").adaptTo(Node.class);
        content.addMixin("mix:referenceable");
        final Node link = node("/home/users/m/mallory/link.svg", "nt:linkedFile");
        link.setProperty("jcr:content", content);
        request(resource("/home/users/m/mallory/link.svg"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
    }

    @Test
    void aNodeThatCannotBeReadIsServedAsADownload() throws Exception
    {
        final Node unreadable = Mockito.mock(Node.class);
        Mockito.when(unreadable.hasProperty(Mockito.anyString())).thenThrow(new RepositoryException("gone"));
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn("/Submissions/s1");
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(unreadable);
        request(resource);

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verify(this.response).setHeader(CONTENT_DISPOSITION, "attachment");
    }

    @Test
    void anEntityIsServedAsItIs() throws Exception
    {
        node("/Submissions/s1", "nt:unstructured").setProperty("title", "A request");
        request(resource("/Submissions/s1"));

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verifyNoInteractions(this.response);
        Mockito.verify(this.chain).doFilter(this.request, this.response);
    }

    @Test
    void aResourceWithNoNodeIsServedAsItIs() throws Exception
    {
        final Resource synthetic = Mockito.mock(Resource.class);
        Mockito.when(synthetic.getPath()).thenReturn("/admin/categories");
        request(synthetic);

        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verifyNoInteractions(this.response);
    }

    @Test
    void theApplicationsOwnFilesAreServedAsTheyAre() throws Exception
    {
        request(storeFile("/libs/iap/resources/media/default/logo-dark.svg"));
        this.filter.doFilter(this.request, this.response, this.chain);
        request(storeFile("/apps/iap/config/logo.svg"));
        this.filter.doFilter(this.request, this.response, this.chain);

        Mockito.verifyNoInteractions(this.response);
    }

    @Test
    void nonSlingRequestsPassThroughUntouched() throws Exception
    {
        final ServletRequest plainRequest = Mockito.mock(ServletRequest.class);
        this.filter.doFilter(plainRequest, this.response, this.chain);

        Mockito.verifyNoInteractions(this.response);
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

    private void request(final Resource resource)
    {
        Mockito.when(this.request.getResource()).thenReturn(resource);
    }

    private Resource resource(final String path)
    {
        return this.context.resourceResolver().getResource(path);
    }

    private Node node(final String path, final String type) throws RepositoryException
    {
        final String parentPath = path.substring(0, path.lastIndexOf('/'));
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        Node parent = session.getRootNode();
        for (final String name : parentPath.substring(1).split("/")) {
            parent = parent.hasNode(name) ? parent.getNode(name) : parent.addNode(name, "nt:unstructured");
        }
        return parent.addNode(path.substring(path.lastIndexOf('/') + 1), type);
    }

    private Resource storeFile(final String path) throws RepositoryException
    {
        final Node file = node(path, "nt:file");
        final Node content = file.addNode("jcr:content", "nt:resource");
        content.setProperty("jcr:data", content.getSession().getValueFactory()
            .createBinary(new ByteArrayInputStream(SVG_WITH_SCRIPT.getBytes(StandardCharsets.UTF_8))));
        content.setProperty("jcr:mimeType", "image/svg+xml");
        content.getSession().save();
        return resource(path);
    }
}
