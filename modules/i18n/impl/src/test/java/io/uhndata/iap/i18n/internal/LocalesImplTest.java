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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocalesImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LocalesImplTest
{
    private final SlingContext context = new SlingContext();

    private final LocalesImpl locales = locales("en", "fr");

    /**
     * A resolver offering the given languages.
     *
     * <p>Activated by hand rather than through the OSGi mock, whose service metadata is generated when the
     * bundle is packaged and so does not exist while these tests run.</p>
     *
     * @param tags the languages the deployment offers
     * @return an activated resolver
     */
    static LocalesImpl locales(final String... tags)
    {
        final LocalesImpl locales = new LocalesImpl();
        locales.activate(new LocalesConfiguration()
        {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType()
            {
                return LocalesConfiguration.class;
            }

            @Override
            public String[] availableLocales()
            {
                return tags;
            }

            @Override
            public String systemLocale()
            {
                return "";
            }
        });
        return locales;
    }

    @AfterEach
    void tidy()
    {
        RequestLocaleHolder.clear();
    }

    @Test
    void readsInTheLanguageTheRequestAskedFor()
    {
        RequestLocaleHolder.set(RequestLocales.from(request("fr", null)));

        assertEquals(Locale.FRENCH, this.locales.getReaderLocale());
    }

    @Test
    void readsInTheLanguageTheBrowserAnnouncedWhereNoneWasAskedFor()
    {
        RequestLocaleHolder.set(RequestLocales.from(request(null, null)));

        assertEquals(Locale.ENGLISH, this.locales.getReaderLocale());
    }

    @Test
    void readsInTheDeploymentsOwnLanguageOffAnyRequest()
    {
        // A scheduled job, or a worker handling something submitted earlier. Right for nobody in particular,
        // which is why rendering a person's notification here would be wrong.
        assertEquals(Locale.ENGLISH, this.locales.getReaderLocale());
    }

    @Test
    void answersARegionalRequestInTheLanguageItHas()
    {
        // Asking for fr-CA where only fr is offered should be answered in French rather than refused
        RequestLocaleHolder.set(RequestLocales.from(request("fr-CA", null)));

        assertEquals(Locale.FRENCH, this.locales.getReaderLocale());
    }

    @Test
    void fallsBackWhereTheLanguageIsOfferedInNoFormAtAll()
    {
        // A page in a language nothing was translated into helps nobody
        RequestLocaleHolder.set(RequestLocales.from(request("de", null)));

        assertEquals(Locale.ENGLISH, this.locales.getReaderLocale());
    }

    @Test
    void leavesAPseudoLocaleAlone()
    {
        // Nothing is stored under those names by design — they are derived on request — so narrowing them to
        // an offered language would resolve the build's own check away
        RequestLocaleHolder.set(RequestLocales.from(request("en-XA", null)));

        assertEquals(Locale.forLanguageTag("en-XA"), this.locales.getReaderLocale());
    }

    @Test
    void answersForAGivenRequestWithoutReachingForTheAmbientOne()
    {
        // Deliberately with nothing on the thread: code holding a request must be able to answer from it
        // alone, and would otherwise be reading whichever request happened to touch this thread last.
        RequestLocaleHolder.clear();

        assertEquals(Locale.FRENCH, this.locales.getReaderLocale(request("fr", null)));
    }

    @Test
    void narrowsAGivenRequestTheSameWayAsTheAmbientOne()
    {
        // The reason this exists at all: what a caller reports back has to be a language the deployment can
        // actually supply, not the one that was asked for
        assertEquals(Locale.ENGLISH, this.locales.getReaderLocale(request("de", null)));
        assertEquals(Locale.FRENCH, this.locales.getReaderLocale(request("fr-CA", null)));
        assertEquals(Locale.forLanguageTag("en-XA"), this.locales.getReaderLocale(request("en-XA", null)));
    }

    @Test
    void stillAnswersSomebodyWhereADeploymentOffersNothing()
    {
        // English is what this platform's own strings are written in, so it is what an unconfigured
        // deployment can actually honour
        assertEquals(List.of(Locale.ENGLISH), locales().getAvailableLocales());
    }

    @Test
    void answersNothingAboutARequestThatIsNotHappening()
    {
        assertTrue(this.locales.getRequestLocale().isEmpty());
    }

    @Test
    void readsTheLanguageOffARequestInHand()
    {
        assertEquals(Locale.FRENCH, this.locales.getRequestLocale(request("fr", null)).orElseThrow());
    }

    @Test
    void readsARememberedLanguageOffARequestInHand()
    {
        assertEquals(Locale.FRENCH, this.locales.getRequestLocale(request(null, "fr")).orElseThrow());
    }

    @Test
    void offersWhatTheDeploymentSaysItOffers()
    {
        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH), this.locales.getAvailableLocales());
    }

    @Test
    void writesItsOwnLogsInTheJvmsLanguageUnlessToldOtherwise()
    {
        assertEquals(Locale.getDefault(), this.locales.getSystemLocale());
    }

    @Test
    void saysSoRatherThanGuessingAPersonsLanguage()
    {
        // Answering the deployment default here would look implemented and quietly send every notification
        // in one language. Nothing stores a preference yet, so nothing can be honoured yet.
        final Authorizable user = Mockito.mock(Authorizable.class);

        assertThrows(UnsupportedOperationException.class, () -> this.locales.getUserPreferredLocale(user));
        assertThrows(UnsupportedOperationException.class, () -> this.locales.getLocaleFor(user));
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
