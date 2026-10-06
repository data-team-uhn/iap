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
package io.uhndata.iap.favicon.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaviconServletTest
{
    private static final byte[] ICO = { 0, 0, 1, 0 };

    private static final byte[] SVG = "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

    private final FaviconServlet servlet = new FaviconServlet();

    private final SlingJakartaHttpServletRequest request = mock(SlingJakartaHttpServletRequest.class);

    private final ResourceResolver resolver = mock(ResourceResolver.class);

    private Resource raster;

    @BeforeEach
    void setUp()
    {
        this.raster = withContent(ICO);
        final Resource vector = withContent(SVG);
        when(this.request.getResource()).thenReturn(this.raster);
        when(this.request.getResourceResolver()).thenReturn(this.resolver);
        when(this.resolver.getResource(FaviconServlet.SVG_PATH)).thenReturn(vector);
    }

    @Test
    void sendsTheVectorIconWhenItIsAcceptedByName() throws IOException
    {
        when(this.request.getHeader("Accept")).thenReturn("image/avif,image/svg+xml,image/*,*/*;q=0.8");

        this.servlet.doGet(this.request, this.response);

        assertEquals("image/svg+xml", this.response.getContentType());
        assertArrayEquals(SVG, this.response.getOutput());
        assertEquals(SVG.length, this.response.getContentLength());
        assertEquals("Accept", this.response.getHeader("Vary"));
    }

    @Test
    void sendsTheRasterIconWhenTheVectorIsNotAccepted() throws IOException
    {
        when(this.request.getHeader("Accept")).thenReturn("image/png,image/*,*/*;q=0.8");

        this.servlet.doGet(this.request, this.response);

        assertEquals("image/x-icon", this.response.getContentType());
        assertArrayEquals(ICO, this.response.getOutput());
        assertEquals("Accept", this.response.getHeader("Vary"));
    }

    @Test
    void datesTheVectorIconByItsOwnModificationTime() throws IOException
    {
        this.raster.getResourceMetadata().setModificationTime(1_000_000L);
        this.resolver.getResource(FaviconServlet.SVG_PATH).getResourceMetadata().setModificationTime(5_000_000L);
        when(this.request.getHeader("Accept")).thenReturn("image/svg+xml");

        this.servlet.doGet(this.request, this.response);

        assertEquals(5_000_000L, lastModified());
    }

    @Test
    void datesTheRasterIconByItsModificationTime() throws IOException
    {
        this.raster.getResourceMetadata().setModificationTime(1_000_000L);

        this.servlet.doGet(this.request, this.response);

        assertEquals(1_000_000L, lastModified());
    }

    @Test
    void omitsTheModificationTimeWhenItIsUnknown() throws IOException
    {
        this.servlet.doGet(this.request, this.response);

        assertFalse(this.response.containsHeader("Last-Modified"));
    }

    @Test
    void sendsTheRasterIconWithoutAnAcceptHeader() throws IOException
    {
        this.servlet.doGet(this.request, this.response);

        assertArrayEquals(ICO, this.response.getOutput());
    }

    @Test
    void fallsBackToTheRasterIconWhenTheVectorIsMissing() throws IOException
    {
        when(this.request.getHeader("Accept")).thenReturn("image/svg+xml");
        when(this.resolver.getResource(FaviconServlet.SVG_PATH)).thenReturn(null);

        this.servlet.doGet(this.request, this.response);

        assertEquals("image/x-icon", this.response.getContentType());
        assertArrayEquals(ICO, this.response.getOutput());
    }

    @Test
    void answersNotFoundWhenTheNodeHasNoContent() throws IOException
    {
        when(this.raster.adaptTo(InputStream.class)).thenReturn(null);

        this.servlet.doGet(this.request, this.response);

        assertEquals(404, this.response.getStatus());
    }

    @Test
    void reportsAContentStreamThatFailsToReadAndToClose()
    {
        final InputStream broken = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException("read");
            }

            @Override
            public void close() throws IOException
            {
                throw new IOException("close");
            }
        };
        when(this.raster.adaptTo(InputStream.class)).thenReturn(broken);

        final IOException failure =
            assertThrows(IOException.class, () -> this.servlet.doGet(this.request, this.response));

        assertEquals("read", failure.getMessage());
        assertEquals("close", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void reportsAContentStreamThatFailsToRead()
    {
        final InputStream broken = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException("read");
            }
        };
        when(this.raster.adaptTo(InputStream.class)).thenReturn(broken);

        final IOException failure =
            assertThrows(IOException.class, () -> this.servlet.doGet(this.request, this.response));

        assertEquals("read", failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void readsTheAcceptHeader()
    {
        assertFalse(FaviconServlet.acceptsSvg(null));
        assertFalse(FaviconServlet.acceptsSvg(""));
        assertFalse(FaviconServlet.acceptsSvg("image/*,*/*"));
        assertFalse(FaviconServlet.acceptsSvg("image/png, image/svg"));
        assertTrue(FaviconServlet.acceptsSvg("image/svg+xml"));
        assertTrue(FaviconServlet.acceptsSvg("image/png , Image/SVG+XML ; q=0.5"));
        assertTrue(FaviconServlet.acceptsSvg("image/svg+xml;level=1"));
        assertTrue(FaviconServlet.acceptsSvg("image/svg+xml;level"));
        assertTrue(FaviconServlet.acceptsSvg("image/svg+xml;q=abc"));
        assertTrue(FaviconServlet.acceptsSvg("image/svg+xml;q=1,image/png"));
        assertFalse(FaviconServlet.acceptsSvg("image/svg+xml;q=0"));
        assertFalse(FaviconServlet.acceptsSvg("image/svg+xml; q=0.0"));
    }

    private long lastModified()
    {
        return ZonedDateTime.parse(this.response.getHeader("Last-Modified"), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli();
    }

    private static Resource withContent(final byte[] bytes)
    {
        final Resource resource = Mockito.mock(Resource.class);
        when(resource.adaptTo(InputStream.class)).thenAnswer(call -> new ByteArrayInputStream(bytes));
        when(resource.getResourceMetadata()).thenReturn(new ResourceMetadata());
        return resource;
    }
}
