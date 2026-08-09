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

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.i18n.api.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessageCatalogServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MessageCatalogServletTest
{
    private final SlingContext context = new SlingContext();

    private ResourceBundleProvider bundles;

    private MessageCatalogServlet servlet;

    @BeforeEach
    void setUp() throws Exception
    {
        this.bundles = Mockito.mock(ResourceBundleProvider.class);
        this.servlet = new MessageCatalogServlet();
        // The real message service, not a stand-in: deriving a pseudo-locale is its work, and these are the
        // tests that describe what a caller actually receives when it asks for one
        inject("messages", MessagesImplTest.messagesBackedBy(this.bundles));
        // The real resolver rather than a stand-in: how a request's language is worked out is exactly what
        // these tests are about, and a stub would only assert that this test agrees with itself
        inject("locales", LocalesImplTest.locales("en", "fr"));
        this.context.create().resource("/libs/iap/messages", "sling:resourceType", "iap/Messages");
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = MessageCatalogServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.servlet, value);
    }

    @Test
    void servesTheInterfaceCatalogueByDefault() throws Exception
    {
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.b", "Second", "iap.a", "First"));

        final MockSlingJakartaHttpServletResponse response = get(Map.of(), Locale.ENGLISH);

        // The charset rides along on the same header, so match the type rather than the whole value
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        final String body = response.getOutputAsString();
        assertTrue(body.contains("\"catalog\":\"" + Messages.INTERFACE + "\""), body);
        assertTrue(body.contains("\"First\"") && body.contains("\"Second\""), body);
    }

    @Test
    void derivesAPseudoLocaleFromTheSourceLanguage() throws Exception
    {
        // Nothing is stored for en-XA. Asking for it must still answer, from the English catalog, or the
        // whole check is testing a translation somebody remembered to write rather than the interface.
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.a", "Username"));

        final String body = get(Map.of(), Locale.forLanguageTag("en-XA")).getOutputAsString();

        assertTrue(body.contains("\"locale\":\"en-XA\""), body);
        // Bracketed and longer than what it came from, so a string that never reached a catalog is obvious
        // on sight and a layout that cannot hold a longer translation fails here rather than in French.
        // Which letters carry which accents is PseudoLocale's business and is tested there.
        final String message = body.replaceAll(".*\"iap.a\":\"([^\"]*)\".*", "$1");
        assertFalse(message.contains("Username"), body);
        assertTrue(message.startsWith("[") && message.endsWith("]"), body);
        assertTrue(message.length() > "Username".length(), body);
    }

    @Test
    void servesEveryKeyInAPseudoLocale() throws Exception
    {
        // The point of deriving rather than authoring: a hand-written pseudo-catalog would be partial, and
        // a key it missed would fall back to English and read as a bug that is not there.
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.a", "One", "iap.b", "Two", "iap.c", "Three"));

        final String body = get(Map.of(), Locale.forLanguageTag("en-XA")).getOutputAsString();

        assertTrue(body.contains("iap.a") && body.contains("iap.b") && body.contains("iap.c"), body);
        assertFalse(body.contains("\"One\"") || body.contains("\"Two\"") || body.contains("\"Three\""), body);
    }

    @Test
    void servesTheShortPseudoLocaleUnmarked() throws Exception
    {
        // en-XB is the length check, and brackets would themselves take room; its own width is the measure
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.a", "Username"));

        final String body = get(Map.of(), Locale.forLanguageTag("en-XB")).getOutputAsString();

        assertTrue(body.contains("\"locale\":\"en-XB\""), body);
        assertFalse(body.contains("["), body);
    }

    @Test
    void leavesAnOrdinaryLocaleAlone() throws Exception
    {
        // en-CA is a real locale that happens to look like the pseudo ones. Only XA and XB are reserved.
        offer(Messages.INTERFACE, Locale.CANADA, Map.of("iap.a", "Username"));

        final String body = get(Map.of(), Locale.CANADA).getOutputAsString();

        assertTrue(body.contains("\"Username\""), body);
    }

    @Test
    void servesTheKeysInASettledOrder() throws Exception
    {
        // Not tidiness: the entity tag is computed from the body, so an unstable order would change it on
        // every request and silently defeat revalidation, without any other test noticing.
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.c", "3", "iap.a", "1", "iap.b", "2"));

        final String body = get(Map.of(), Locale.ENGLISH).getOutputAsString();

        assertTrue(body.indexOf("iap.a") < body.indexOf("iap.b"), body);
        assertTrue(body.indexOf("iap.b") < body.indexOf("iap.c"), body);
    }

    @Test
    void servesTheCatalogueThatWasAskedFor() throws Exception
    {
        offer(Messages.CONTENT, Locale.ENGLISH, Map.of("/libs/iap/conf/LoginPage/introText", "Welcome"));

        final String body = get(Map.of("catalog", Messages.CONTENT), Locale.ENGLISH).getOutputAsString();

        assertTrue(body.contains("\"catalog\":\"" + Messages.CONTENT + "\""), body);
        assertTrue(body.contains("Welcome"), body);
    }

    @Test
    void answersInTheLanguageAskedForByName() throws Exception
    {
        offer(Messages.INTERFACE, Locale.FRENCH, Map.of("iap.greeting", "Bonjour"));

        final String body = get(Map.of("locale", "fr"), Locale.ENGLISH).getOutputAsString();

        assertTrue(body.contains("\"locale\":\"fr\""), body);
        assertTrue(body.contains("Bonjour"), body);
    }

    @Test
    void otherwiseAnswersInTheLanguageTheRequestAskedFor() throws Exception
    {
        // No `locale` parameter: the browser's Accept-Language, which Sling has already resolved onto the
        // request, is what decides. This is the case that matters for the sign-in page.
        offer(Messages.INTERFACE, Locale.FRENCH, Map.of("iap.greeting", "Bonjour"));

        final String body = get(Map.of(), Locale.FRENCH).getOutputAsString();

        assertTrue(body.contains("\"locale\":\"fr\""), body);
        assertTrue(body.contains("Bonjour"), body);
    }

    @Test
    void treatsBlankParametersAsAbsentOnes() throws Exception
    {
        // A client building the URL from empty state sends `?catalog=&locale=`, which should mean "give me
        // the usual" rather than "give me the catalog named empty string in the locale named empty string".
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.greeting", "Good morning"));

        final String body = get(Map.of("catalog", "", "locale", ""), Locale.ENGLISH).getOutputAsString();

        assertTrue(body.contains("\"catalog\":\"" + Messages.INTERFACE + "\""), body);
        assertTrue(body.contains("Good morning"), body);
    }

    @Test
    void answersWithAnEmptyCatalogueWhenThereIsNoSuchOne() throws Exception
    {
        when(this.bundles.getResourceBundle(any(), any())).thenReturn(null);

        final String body = get(Map.of("catalog", "iap.nonexistent"), Locale.ENGLISH).getOutputAsString();

        // An empty catalog rather than an error: a page asking for one that has not been written yet should
        // render in the source language, not fail to render at all.
        assertTrue(body.contains("\"messages\":{}"), body);
    }

    @Test
    void answersNotModifiedWhenTheCallerAlreadyHasThisCatalogue() throws Exception
    {
        offer(Messages.INTERFACE, Locale.ENGLISH, Map.of("iap.greeting", "Good morning"));
        final MockSlingJakartaHttpServletResponse first = get(Map.of(), Locale.ENGLISH);
        final String tag = first.getHeader("ETag");

        final MockSlingJakartaHttpServletRequest request = request(Map.of(), Locale.ENGLISH);
        request.setHeader("If-None-Match", tag);
        final MockSlingJakartaHttpServletResponse second = new MockSlingJakartaHttpServletResponse();
        this.servlet.doGet(request, second);

        assertEquals(304, second.getStatus());
        assertEquals("", second.getOutputAsString());
    }

    private void offer(final String catalog, final Locale locale, final Map<String, String> entries)
    {
        when(this.bundles.getResourceBundle(eq(catalog), eq(locale))).thenReturn(bundleOf(entries));
    }

    private MockSlingJakartaHttpServletRequest request(final Map<String, String> parameters, final Locale locale)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/libs/iap/messages"));
        request.setParameterMap(new LinkedHashMap<>(parameters));
        request.setLocale(locale);
        return request;
    }

    private MockSlingJakartaHttpServletResponse get(final Map<String, String> parameters, final Locale locale)
        throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doGet(request(parameters, locale), response);
        return response;
    }

    private static ResourceBundle bundleOf(final Map<String, String> entries)
    {
        return new ListResourceBundle()
        {
            @Override
            protected Object[][] getContents()
            {
                return entries.entrySet().stream()
                    .map(entry -> new Object[] { entry.getKey(), entry.getValue() })
                    .toArray(Object[][]::new);
            }
        };
    }
}
