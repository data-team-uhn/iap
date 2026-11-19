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
package io.uhndata.iap.startup.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupPlaceholderServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StartupPlaceholderServletTest
{
    @Test
    void servesTheStartupPageForEveryRequest() throws Exception
    {
        final StartupPlaceholderServlet servlet = new StartupPlaceholderServlet(new StartupPage("<p>Starting up</p>"));
        final StringWriter body = new StringWriter();
        final HttpServletResponse response = responseWritingTo(body);

        servlet.service(mock(HttpServletRequest.class), response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response).setHeader("Cache-Control", "no-store");
        assertEquals("<p>Starting up</p>", body.toString());
    }

    @Test
    void servesTheBundledPageWhenActivatedByOsgi() throws Exception
    {
        final StartupPlaceholderServlet servlet = new StartupPlaceholderServlet();

        assertEquals(bodyOf(StartupPage.bundled()), bodyServedBy(servlet));
    }

    private static String bodyOf(final StartupPage page) throws IOException
    {
        final StringWriter body = new StringWriter();
        page.serve(responseWritingTo(body));
        return body.toString();
    }

    private static String bodyServedBy(final StartupPlaceholderServlet servlet) throws Exception
    {
        final StringWriter body = new StringWriter();
        servlet.service(mock(HttpServletRequest.class), responseWritingTo(body));
        return body.toString();
    }

    private static HttpServletResponse responseWritingTo(final StringWriter body) throws IOException
    {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
