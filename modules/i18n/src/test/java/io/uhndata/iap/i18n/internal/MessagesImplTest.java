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

    private ResourceBundleProvider bundles;

    private MessagesImpl messages;

    @BeforeEach
    void setUp() throws Exception
    {
        this.bundles = Mockito.mock(ResourceBundleProvider.class);
        this.messages = new MessagesImpl();
        final Field field = MessagesImpl.class.getDeclaredField("bundles");
        field.setAccessible(true);
        field.set(this.messages, this.bundles);
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
