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

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupPage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StartupPageTest
{
    private HttpServletResponse response;

    private StringWriter body;

    @BeforeEach
    void setUp() throws IOException
    {
        this.response = mock(HttpServletResponse.class);
        this.body = new StringWriter();
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
    }

    @Test
    void servesThePageAsAnUncachedServiceUnavailableAnswer() throws IOException
    {
        new StartupPage("<p>Starting up</p>").serve(this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(this.response).setContentType("text/html;charset=UTF-8");
        verify(this.response).setHeader("Cache-Control", "no-store");
        assertEquals("<p>Starting up</p>", this.body.toString());
    }

    @Test
    void readsAPageFromTheClasspathAsUtf8() throws IOException
    {
        StartupPage.fromResource("/startup_page_test.html").serve(this.response);

        assertTrue(this.body.toString().endsWith("-->\n<p>Démarrage en cours</p>\n"), this.body::toString);
    }

    @Test
    void readsTheWholeBundledPage() throws IOException
    {
        StartupPage.bundled().serve(this.response);

        assertTrue(this.body.toString().endsWith("</html>\n"), this.body::toString);
    }

    @Test
    void refusesAPageThatIsNotOnTheClasspath()
    {
        final NullPointerException thrown =
            assertThrows(NullPointerException.class, () -> StartupPage.fromResource("/absent.html"));

        assertEquals("No startup page at /absent.html", thrown.getMessage());
    }
}
