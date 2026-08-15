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

import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RequestLocales}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RequestLocalesTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void readsTheLanguageNamedInTheUrl()
    {
        assertEquals(Locale.FRENCH, RequestLocales.from(request("fr", null)).chosen().orElseThrow());
    }

    @Test
    void readsTheLanguageThisBrowserWasToldToRemember()
    {
        assertEquals(Locale.FRENCH, RequestLocales.from(request(null, "fr")).chosen().orElseThrow());
    }

    @Test
    void prefersTheUrlOverTheCookie()
    {
        // The more recent of the two acts, and the only one that could have been performed just now
        assertEquals(Locale.ENGLISH, RequestLocales.from(request("en", "fr")).chosen().orElseThrow());
    }

    @Test
    void asksForNothingWhenNothingWasAsked()
    {
        assertTrue(RequestLocales.from(request(null, null)).chosen().isEmpty());
    }

    @Test
    void keepsWhatTheBrowserAnnouncedApartFromWhatWasChosen()
    {
        // Collapsing the two would make a default indistinguishable from a decision, and a stored preference
        // could then never outrank a header nobody set on purpose
        final RequestLocales locales = RequestLocales.from(request(null, null));

        assertTrue(locales.chosen().isEmpty());
        assertEquals(Locale.ENGLISH, locales.announced());
    }

    @Test
    void refusesATagThatIsNotOne()
    {
        // This value is written back out in a cookie, so it is checked on the way in rather than trusted for
        // having arrived in a request
        assertTrue(RequestLocales.parse("../../etc/passwd").isEmpty());
        assertTrue(RequestLocales.parse("fr; DROP TABLE").isEmpty());
        assertTrue(RequestLocales.parse("").isEmpty());
        // Well-formed as a tag, yet names no language — "und" is how a tag says "undetermined". The shape
        // check alone would let it through, and it would then be asked for as if it were a language.
        assertTrue(RequestLocales.parse("und").isEmpty());
        assertTrue(RequestLocales.parse(null).isEmpty());
    }

    @Test
    void ignoresAMalformedCookieRatherThanFailing()
    {
        assertTrue(RequestLocales.from(request(null, "not a language")).chosen().isEmpty());
    }

    @Test
    void readsARegionalLanguage()
    {
        assertEquals(Locale.CANADA_FRENCH, RequestLocales.parse("fr-CA").orElseThrow());
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
