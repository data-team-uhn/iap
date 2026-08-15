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
package io.uhndata.iap.scripting;

import java.util.Locale;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.i18n.api.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigMetadata}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConfigMetadataTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(ConfigMetadata.class);
    }

    @Test
    void translatesConfigurationForAReaderWhoAsksForAnotherLanguage()
    {
        // The sign-in page's intro text reaches the browser as a <meta> tag rendered here, not as JSON, so
        // this is where a translation of shipped configuration has to be applied.
        this.context.create().resource("/libs/iap/conf/LoginPage", Map.of("introText", "Welcome"));
        offer(Locale.FRENCH, Map.of("/libs/iap/conf/LoginPage/introText", "Bienvenue"));

        assertEquals("Bienvenue", forA(Locale.FRENCH).getProperties().get("introText"));
    }

    @Test
    void leavesConfigurationAsShippedWhereNoTranslationExists()
    {
        this.context.create().resource("/libs/iap/conf/Version", Map.of("version", "1.0.0"));
        offer(Locale.FRENCH, Map.of("/libs/iap/conf/LoginPage/introText", "Bienvenue"));

        // Additive: a version number has no translation and should not acquire one
        assertEquals("1.0.0", forA(Locale.FRENCH).getProperties().get("version"));
    }

    @Test
    void tellsThePageWhichLanguageItIsIn()
    {
        // Rendered into <html lang> while the page is being parsed: a screen reader picks its voice and its
        // pronunciation from that attribute, and a script correcting it afterwards is already too late
        assertEquals("fr", forANamed(Locale.ENGLISH, "fr").getLanguage());
        assertEquals("", forA(Locale.ENGLISH).getLanguage());
    }

    @Test
    void tellsThePageWhichWayItReads()
    {
        assertEquals("ltr", forA(Locale.ENGLISH).getDirection());
    }

    @Test
    void tellsThePageToTurnAroundForALanguageThatReadsThatWay()
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.create().resource("/content/mirrored"));
        request.setAttribute("io.uhndata.iap.i18n.direction", "rtl");

        assertEquals("rtl", request.adaptTo(ConfigMetadata.class).getDirection());
    }

    @Test
    void namesNoLanguageWhereThereIsNoReaderToHaveOne()
    {
        // Adapted from a plain resource — a background job, say — there is nobody the page is being rendered
        // for, so it claims neither a language nor a direction rather than inventing one
        final ConfigMetadata config =
            this.context.create().resource("/content/nobody").adaptTo(ConfigMetadata.class);

        assertEquals("", config.getLanguage());
        assertEquals("ltr", config.getDirection());
    }

    @Test
    void prefersTheLanguageTheRequestAsksForOverTheOneTheBrowserAnnounces()
    {
        // Somebody who has landed on a sign-in page in a language they do not read has to be able to say so,
        // and changing a browser preference to read one page is not something to ask of a visitor.
        this.context.create().resource("/libs/iap/conf/LoginPage", Map.of("introText", "Welcome"));
        offer(Locale.FRENCH, Map.of("/libs/iap/conf/LoginPage/introText", "Bienvenue"));

        assertEquals("Bienvenue", forANamed(Locale.ENGLISH, "fr").getProperties().get("introText"));
    }

    @Test
    void fallsBackToTheBrowserWhenTheRequestAsksForNothing()
    {
        this.context.create().resource("/libs/iap/conf/LoginPage", Map.of("introText", "Welcome"));
        offer(Locale.FRENCH, Map.of("/libs/iap/conf/LoginPage/introText", "Bienvenue"));

        assertEquals("Bienvenue", forANamed(Locale.FRENCH, "").getProperties().get("introText"));
    }

    @Test
    void leavesConfigurationAsShippedWhenThereIsNoReader()
    {
        // Adapted from a plain resource rather than a request — a background job, say. There is nobody whose
        // language it could be, so nothing is translated.
        this.context.create().resource("/libs/iap/conf/LoginPage", Map.of("introText", "Welcome"));
        offer(Locale.FRENCH, Map.of("/libs/iap/conf/LoginPage/introText", "Bienvenue"));
        final Resource resource = this.context.create().resource("/content/page");

        assertEquals("Welcome", resource.adaptTo(ConfigMetadata.class).getProperties().get("introText"));
    }

    /**
     * A request from a browser announcing one language while the URL names another.
     *
     * @param announced what the browser announced
     * @param named the language the request asked for, or empty for none
     * @return the model built from that request
     */
    private ConfigMetadata forANamed(final Locale announced, final String named)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.create().resource("/content/named" + named));
        request.setLocale(announced);
        if (!named.isEmpty()) {
            request.setAttribute("io.uhndata.iap.i18n.locale", Locale.forLanguageTag(named));
        }
        return request.adaptTo(ConfigMetadata.class);
    }

    private ConfigMetadata forA(final Locale locale)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.create().resource("/content/page" + locale));
        request.setLocale(locale);
        return request.adaptTo(ConfigMetadata.class);
    }

    /**
     * A set of translations for one language, as the message service would answer them.
     *
     * <p>A stand-in rather than the real service, which lives in a bundle this one cannot depend on — it
     * depends on this one. What a missing translation does is that service's contract and is tested against
     * it; what matters here is only that this model asks for the right key in the right language and renders
     * whatever comes back.</p>
     *
     * @param locale the language the translations are in
     * @param entries the translations, by the path of the property each one translates
     */
    private void offer(final Locale locale, final Map<String, String> entries)
    {
        final Messages messages = Mockito.mock(Messages.class);
        Mockito.when(messages.translate(Mockito.eq(Messages.CONTENT), Mockito.anyString(), Mockito.any(),
            Mockito.anyString())).thenAnswer(call -> {
                final String key = call.getArgument(1);
                return locale.equals(call.getArgument(2)) ? entries.getOrDefault(key, call.getArgument(3))
                    : call.getArgument(3);
            });
        this.context.registerService(Messages.class, messages,
            Map.of(Constants.SERVICE_RANKING, Integer.valueOf(1000)));
    }

    @Test
    void adaptsAnyResourceToModel()
    {
        // The model reads a fixed repository path, not the adapted-from resource, so it must adapt
        // successfully even from a resource completely unrelated to /libs/iap/conf.
        final Resource resource = this.context.create().resource("/content/unrelated");
        assertNotNull(resource.adaptTo(ConfigMetadata.class));
    }

    @Test
    void collectsPropertiesFromConfRoot()
    {
        this.context.create().resource("/libs/iap/conf", Map.of("themeColor", "blue"));
        final Resource resource = this.context.create().resource("/content/page");

        final ConfigMetadata config = resource.adaptTo(ConfigMetadata.class);

        assertEquals("blue", config.getProperties().get("themeColor"));
    }

    @Test
    void collectsPropertiesFromNestedNodesIntoAFlatMap()
    {
        this.context.create().resource("/libs/iap/conf/Version", Map.of("version", "1.0.0"));
        this.context.create().resource("/libs/iap/conf/Media", Map.of("logoDark", "/logo.png"));
        final Resource resource = this.context.create().resource("/content/page");

        final Map<String, String> properties = resource.adaptTo(ConfigMetadata.class).getProperties();

        assertEquals("1.0.0", properties.get("version"));
        assertEquals("/logo.png", properties.get("logoDark"));
    }

    @Test
    void excludesJcrPrefixedProperties()
    {
        this.context.create().resource("/libs/iap/conf/Version",
            Map.of("jcr:primaryType", "nt:unstructured", "version", "1.0.0"));
        final Resource resource = this.context.create().resource("/content/page");

        final Map<String, String> properties = resource.adaptTo(ConfigMetadata.class).getProperties();

        assertEquals("1.0.0", properties.get("version"));
        assertTrue(properties.keySet().stream().noneMatch(name -> name.startsWith("jcr:")));
    }

    @Test
    void excludesBlankProperties()
    {
        this.context.create().resource("/libs/iap/conf/AppVersion", Map.of("appVersion", ""));
        final Resource resource = this.context.create().resource("/content/page");

        final Map<String, String> properties = resource.adaptTo(ConfigMetadata.class).getProperties();

        assertTrue(properties.isEmpty());
    }

    @Test
    void returnsEmptyMapWhenConfRootIsMissing()
    {
        final Resource resource = this.context.create().resource("/content/page");

        final Map<String, String> properties = resource.adaptTo(ConfigMetadata.class).getProperties();

        assertNotNull(properties);
        assertTrue(properties.isEmpty());
    }
}
