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
package io.uhndata.iap.i18n.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RequestLocaleFilter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RequestLocaleFilterTest
{
    private final SlingContext context = new SlingContext();

    private final RequestLocaleFilter filter = new RequestLocaleFilter();

    @BeforeEach
    void setUp() throws Exception
    {
        // The real resolver: what the filter leaves on the request is the resolved language, so a stub here
        // would only assert that this test agrees with itself
        final java.lang.reflect.Field field = RequestLocaleFilter.class.getDeclaredField("localeService");
        field.setAccessible(true);
        field.set(this.filter, LocalesImplTest.locales("en", "fr"));
    }

    @AfterEach
    void tidy()
    {
        RequestLocaleHolder.clear();
    }

    @Test
    void leavesTheResolvedLanguageOnTheRequest() throws Exception
    {
        // The page shell renders this into <html lang> and <html dir> before a script has run, so the page
        // arrives the right way round rather than turning around once the interface strings catch up
        final MockSlingJakartaHttpServletRequest request = request("fr", null);

        this.filter.doFilter(request, new MockSlingJakartaHttpServletResponse(), (rq, rs) -> { });

        assertEquals(Locale.FRENCH, request.getAttribute(RequestLocales.ATTRIBUTE));
        assertEquals("ltr", request.getAttribute(RequestLocales.DIRECTION_ATTRIBUTE));
    }

    @Test
    void saysWhichWayTheResolvedLanguageReads() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request = request("en-XB", null);

        this.filter.doFilter(request, new MockSlingJakartaHttpServletResponse(), (rq, rs) -> { });

        assertEquals("rtl", request.getAttribute(RequestLocales.DIRECTION_ATTRIBUTE));
    }

    @Test
    void putsTheRequestsLanguageWhereItCanBeFound() throws Exception
    {
        final Locale[] seen = new Locale[1];

        this.filter.doFilter(request("fr", null), new MockSlingJakartaHttpServletResponse(),
            (rq, rs) -> seen[0] = RequestLocaleHolder.get().flatMap(RequestLocales::chosen).orElse(null));

        assertEquals(Locale.FRENCH, seen[0]);
    }

    @Test
    void takesTheLanguageBackAfterwards() throws Exception
    {
        // Threads are pooled: a value left behind is handed to whoever is served next on this thread, and a
        // page in a stranger's language would never reproduce
        this.filter.doFilter(request("fr", null), new MockSlingJakartaHttpServletResponse(), (rq, rs) -> { });

        assertTrue(RequestLocaleHolder.get().isEmpty());
    }

    @Test
    void takesItBackEvenWhereTheRequestFailed()
    {
        final FilterChain failing = (rq, rs) -> {
            throw new IllegalStateException("something went wrong further down");
        };

        assertThrows(IllegalStateException.class,
            () -> this.filter.doFilter(request("fr", null), new MockSlingJakartaHttpServletResponse(), failing));
        assertTrue(RequestLocaleHolder.get().isEmpty());
    }

    @Test
    void remembersALanguageNamedInTheUrl() throws Exception
    {
        // So the choice survives the one navigation certain to happen next: signing in
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.filter.doFilter(request("fr", null), response, (rq, rs) -> { });

        assertEquals("fr", cookie(response).getValue());
    }

    @Test
    void keepsTheCookieToItself() throws Exception
    {
        // Nothing in the browser reads it: the server resolves the language and answers with it, so there is
        // one implementation of the rules rather than two that drift apart
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.filter.doFilter(request("fr", null), response, (rq, rs) -> { });

        assertTrue(cookie(response).isHttpOnly());
        assertEquals("/", cookie(response).getPath());
    }

    @Test
    void writesNothingBackWhereNoChoiceWasMade() throws Exception
    {
        // A request whose language came from the cookie has made no new choice, and rewriting it every time
        // would extend the cookie's life indefinitely from a single visit
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.filter.doFilter(request(null, "fr"), response, (rq, rs) -> { });

        assertNull(cookie(response));
    }

    @Test
    void writesNothingBackForATagThatIsNotOne() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.filter.doFilter(request("../../etc", null), response, (rq, rs) -> { });

        assertNull(cookie(response));
    }

    @Test
    void remembersNothingWhereThereIsNoResponseToRememberItOn() throws Exception
    {
        final boolean[] continued = new boolean[1];

        this.filter.doFilter(request("fr", null), Mockito.mock(jakarta.servlet.ServletResponse.class),
            (rq, rs) -> continued[0] = true);

        assertTrue(continued[0]);
    }

    @Test
    void letsThroughAnythingThatIsNotAnHttpRequest() throws Exception
    {
        final boolean[] continued = new boolean[1];

        this.filter.doFilter(Mockito.mock(jakarta.servlet.ServletRequest.class),
            new MockSlingJakartaHttpServletResponse(), (rq, rs) -> continued[0] = true);

        assertTrue(continued[0]);
    }

    /** The remembered-language cookie this response sets, if it sets one. */
    private static Cookie cookie(final MockSlingJakartaHttpServletResponse response)
    {
        final Cookie[] cookies = response.getCookies();
        return cookies == null ? null : Arrays.stream(cookies)
            .filter(each -> RequestLocales.COOKIE.equals(each.getName()))
            .findFirst().orElse(null);
    }

    private MockSlingJakartaHttpServletRequest request(final String named, final String remembered)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setLocale(Locale.ENGLISH);
        if (named != null) {
            request.setParameterMap(Map.of(RequestLocales.LOCALE, named));
        }
        if (remembered != null) {
            request.addCookie(new Cookie(RequestLocales.COOKIE, remembered));
        }
        return request;
    }
}
