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
package io.uhndata.iap.documentation.internal;

import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.documentation.api.DocumentedItem;
import io.uhndata.iap.documentation.api.SelfDocumenting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentationServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentationServletTest
{
    private final SlingContext context = new SlingContext();

    private final DocumentationServlet servlet = new DocumentationServlet();

    /**
     * A fixed documentation catalogue served in the tests.
     */
    private static final class Catalogue implements SelfDocumenting
    {
        @Override
        public String getDocumentationTitle()
        {
            return "Things";
        }

        @Override
        public String getDocumentationIntro()
        {
            return null;
        }

        @Override
        public List<? extends DocumentedItem> getDocumentedItems()
        {
            return List.of();
        }
    }

    @BeforeEach
    void setUp()
    {
        this.context.create().resource("/Things", "sling:resourceType", "iap/ThingsHomepage");
    }

    @Test
    void acceptsDocumentedNodes() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(DocumentationServlet.MIXIN)).thenReturn(true);
        this.context.registerAdapter(Resource.class, Node.class, node);

        assertTrue(this.servlet.accepts(request("md")));
    }

    @Test
    void declinesUndocumentedNodes() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(DocumentationServlet.MIXIN)).thenReturn(false);
        this.context.registerAdapter(Resource.class, Node.class, node);

        assertFalse(this.servlet.accepts(request("md")));
    }

    @Test
    void declinesNonJcrResources()
    {
        // No Node adapter registered, the resource cannot be adapted
        assertFalse(this.servlet.accepts(request("md")));
    }

    @Test
    void declinesOnRepositoryErrors() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(DocumentationServlet.MIXIN))
            .thenThrow(new RepositoryException("unavailable"));
        this.context.registerAdapter(Resource.class, Node.class, node);

        assertFalse(this.servlet.accepts(request("md")));
    }

    @Test
    void servesMarkdown() throws Exception
    {
        this.context.registerAdapter(Resource.class, SelfDocumenting.class, new Catalogue());

        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.service(request("md"), response);

        assertEquals("text/markdown;charset=UTF-8", response.getContentType());
        assertEquals("# Things\n", response.getOutputAsString());
    }

    @Test
    void servesJson() throws Exception
    {
        this.context.registerAdapter(Resource.class, SelfDocumenting.class, new Catalogue());

        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.service(request("json"), response);

        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals("{\"title\":\"Things\",\"items\":{}}", response.getOutputAsString());
    }

    @Test
    void reportsMissingDocumentation() throws Exception
    {
        // The node carries the mixin, but no model provides a SelfDocumenting adaptation
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.service(request("json"), response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getOutputAsString().contains("/Things"));
    }

    private MockSlingJakartaHttpServletRequest request(final String extension)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/Things"));
        ((MockRequestPathInfo) request.getRequestPathInfo()).setSelectorString(DocumentationServlet.SELECTOR);
        ((MockRequestPathInfo) request.getRequestPathInfo()).setExtension(extension);
        return request;
    }
}
