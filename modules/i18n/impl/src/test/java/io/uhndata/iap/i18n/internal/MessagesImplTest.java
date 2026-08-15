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
import java.util.Collections;
import java.util.Enumeration;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.sling.i18n.ResourceBundleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.i18n.api.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessagesImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class MessagesImplTest
{
    private static final String GREETING = "iap.test.greeting";

    /** A content key, which is the path of the property it translates rather than a name somebody chose. */
    private static final String PATH = "/Categories/Retrospective/label";

    private ResourceBundleProvider bundles;

    private MessagesImpl messages;

    @BeforeEach
    void setUp() throws Exception
    {
        this.bundles = Mockito.mock(ResourceBundleProvider.class);
        this.messages = messagesBackedBy(this.bundles);
    }

    /**
     * A working service over a given set of catalogs.
     *
     * <p>Shared with the tests of the things that call it, which use the real service rather than a stand-in:
     * what a pseudo-locale does to a message is decided here, so a stubbed one would let those tests pass
     * while the page they describe came out in plain English.</p>
     *
     * @param bundles where the catalogs come from
     * @return the service under test
     * @throws Exception if the field cannot be set, which would be a rename of it
     */
    static MessagesImpl messagesBackedBy(final ResourceBundleProvider bundles) throws Exception
    {
        final MessagesImpl messages = new MessagesImpl();
        final Field field = MessagesImpl.class.getDeclaredField("bundles");
        field.setAccessible(true);
        field.set(messages, bundles);
        return messages;
    }

    @Test
    void answersWithTheMessageForAKey()
    {
        catalogue(Locale.ENGLISH, Map.of(GREETING, "Good morning"));

        assertEquals("Good morning", this.messages.get(Messages.INTERFACE, GREETING, Locale.ENGLISH));
    }

    @Test
    void answersWithTheKeyWhenTheCatalogueHasNoSuchMessage()
    {
        catalogue(Locale.ENGLISH, Map.of("iap.test.other", "Something else"));

        // Visible on screen and harmless, which is what a runtime miss should be. Stopping a missing key from
        // reaching a release is the build key check's job, not this one's.
        assertEquals(GREETING, this.messages.get(Messages.INTERFACE, GREETING, Locale.ENGLISH));
    }

    @Test
    void answersWithTheKeyWhenThereIsNoCatalogueAtAll()
    {
        when(this.bundles.getResourceBundle(any(), any())).thenReturn(null);

        assertEquals(GREETING, this.messages.get(Messages.INTERFACE, GREETING, Locale.ENGLISH));
    }

    @Test
    void substitutesArgumentsByName()
    {
        // Named, not numbered: a translator may reorder them, repeat one, or drop one, and real translations do
        catalogue(Locale.ENGLISH, Map.of(GREETING, "Good morning, {name}"));

        assertEquals("Good morning, Ada",
            this.messages.format(Messages.INTERFACE, GREETING, Locale.ENGLISH, Map.of("name", "Ada")));
    }

    @Test
    void leavesAMessageWithNoArgumentsExactlyAsWritten()
    {
        // The apostrophe is the point: ICU reads it as its quoting character, so running ordinary prose through
        // the formatter would silently eat it. Most messages have no arguments, and many languages have
        // apostrophes.
        catalogue(Locale.ENGLISH, Map.of(GREETING, "Today's requests"));

        assertEquals("Today's requests", this.messages.format(Messages.INTERFACE, GREETING, Locale.ENGLISH, Map.of()));
    }

    @Test
    void pluralisesByTheLanguagesOwnRulesRatherThanEnglishs()
    {
        // The whole reason for ICU rather than java.text: French counts 0 as singular, English does not. A
        // formatter without CLDR data cannot express this, and the bug is invisible to anyone reading English.
        catalogue(Locale.FRENCH,
            Map.of(GREETING, "{count, plural, one {# nouveau message} other {# nouveaux messages}}"));

        assertEquals("0 nouveau message",
            this.messages.format(Messages.INTERFACE, GREETING, Locale.FRENCH, Map.of("count", 0)));
    }

    @Test
    void fallsBackToTheInstanceDefaultWhenNoLocaleIsGiven()
    {
        catalogue(null, Map.of(GREETING, "Good morning, {name}"));

        assertEquals("Good morning, Ada",
            this.messages.format(Messages.INTERFACE, GREETING, null, Map.of("name", "Ada")));
    }

    @Test
    void answersWithTheShippedTextWhenNobodyHasTranslatedIt()
    {
        // The ordinary case for content, and what makes the mechanism additive: a deployment that has
        // translated half its pages renders the other half in the language it was written in.
        content(Locale.FRENCH, Map.of("/somewhere/else", "Autre"));

        assertEquals("Retrospective studies",
            this.messages.translate(Messages.CONTENT, PATH, Locale.FRENCH, "Retrospective studies"));
    }

    @Test
    void answersWithTheShippedTextWhenTheCatalogueEchoesMissingKeys()
    {
        // Sling's own bundles answer a miss with the key itself rather than throwing, so allowing only for
        // the exception replaces every untranslated property with its own repository path. That is not
        // hypothetical: it is what a rendered page showed before this check existed.
        when(this.bundles.getResourceBundle(eq(Messages.CONTENT), eq(Locale.FRENCH)))
            .thenReturn(new ResourceBundle()
            {
                @Override
                protected Object handleGetObject(final String key)
                {
                    return key;
                }

                @Override
                public Enumeration<String> getKeys()
                {
                    return Collections.emptyEnumeration();
                }
            });

        assertEquals("Retrospective studies",
            this.messages.translate(Messages.CONTENT, PATH, Locale.FRENCH, "Retrospective studies"));
    }

    @Test
    void answersWithTheShippedTextWhenThereIsNoCatalogueForThatLanguage()
    {
        when(this.bundles.getResourceBundle(any(), any())).thenReturn(null);

        assertEquals("Retrospective studies",
            this.messages.translate(Messages.CONTENT, PATH, Locale.FRENCH, "Retrospective studies"));
    }

    @Test
    void answersWithTheTranslationWhereThereIsOne()
    {
        content(Locale.FRENCH, Map.of(PATH, "Études rétrospectives"));

        assertEquals("Études rétrospectives",
            this.messages.translate(Messages.CONTENT, PATH, Locale.FRENCH, "Retrospective studies"));
    }

    @Test
    void disfiguresCataloguedContentForAPseudoLocale()
    {
        // How server-rendered text takes part in the check at all: the source-language catalog is what says
        // this property is prose somebody reads, so the pseudo-locale can disfigure it and a layout that
        // cannot hold a longer version of it fails in a build.
        content(Locale.ENGLISH, Map.of(PATH, "Welcome"));

        final String disfigured =
            this.messages.translate(Messages.CONTENT, PATH, Locale.forLanguageTag("en-XA"), "Welcome");

        assertTrue(disfigured.startsWith("[") && disfigured.endsWith("]"), disfigured);
        assertFalse(disfigured.contains("Welcome"), disfigured);
    }

    @Test
    void leavesUncataloguedContentAloneEvenUnderAPseudoLocale()
    {
        // Everything a page is built from arrives here, not only its prose: resource types, identifiers, the
        // list of languages the switcher offers. Disfiguring those would break the page rather than test it,
        // and mangling that last one would leave a reader in a pseudo-locale with no way back out.
        content(Locale.ENGLISH, Map.of());

        assertEquals("en fr",
            this.messages.translate(Messages.CONTENT, PATH, Locale.forLanguageTag("en-XA"), "en fr"));
    }

    @Test
    void disfiguresEveryMessageInACatalogue()
    {
        catalogue(Locale.ENGLISH, Map.of(GREETING, "Good morning", "iap.test.other", "Something else"));

        final Map<String, String> all = this.messages.getAll(Messages.INTERFACE, Locale.forLanguageTag("en-XA"));

        assertEquals(2, all.size());
        all.values().forEach(message -> assertTrue(message.startsWith("["), message));
    }

    @Test
    void answersWithNothingWhenThereIsNoSuchCatalogue()
    {
        when(this.bundles.getResourceBundle(any(), any())).thenReturn(null);

        // Empty rather than an error: a page asking for a catalog nobody has written yet should render in
        // the source language rather than fail to render
        assertTrue(this.messages.getAll("iap.nonexistent", Locale.ENGLISH).isEmpty());
    }

    @Test
    void leavesAMissingKeyLegibleUnderAPseudoLocale()
    {
        // A key answered with itself is the signal that something is missing. Dressed up as a
        // pseudo-translation it would look like every other string on the page and stop being a signal.
        catalogue(Locale.ENGLISH, Map.of());

        assertEquals(GREETING, this.messages.get(Messages.INTERFACE, GREETING, Locale.forLanguageTag("en-XA")));
    }

    private void content(final Locale locale, final Map<String, String> entries)
    {
        when(this.bundles.getResourceBundle(eq(Messages.CONTENT), eq(locale))).thenReturn(bundleOf(entries));
    }

    private void catalogue(final Locale locale, final Map<String, String> entries)
    {
        when(this.bundles.getResourceBundle(eq(Messages.INTERFACE), eq(locale))).thenReturn(bundleOf(entries));
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
