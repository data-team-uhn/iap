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

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.i18n.api.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocalizeProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LocalizeProcessorTest
{
    private static final String TITLE_PATH = "/Categories/Retrospective/label";

    private final SlingContext context = new SlingContext();

    private ResourceBundleProvider bundles;

    private LocalizeProcessor processor;

    @BeforeEach
    void setUp() throws Exception
    {
        this.bundles = Mockito.mock(ResourceBundleProvider.class);
        this.processor = new LocalizeProcessor();
        final Field field = LocalizeProcessor.class.getDeclaredField("bundles");
        field.setAccessible(true);
        field.set(this.processor, this.bundles);
    }

    @Test
    void isOnByDefaultAndRunsAfterTheProcessorsThatDecideValues()
    {
        assertEquals("localize", this.processor.getName());
        assertEquals(20, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(resource(".json")));
    }

    @Test
    void replacesAValueWithItsTranslation() throws Exception
    {
        translations(Locale.FRENCH, Map.of(TITLE_PATH, "Études rétrospectives"));
        this.processor.start(resource(".localize:fr.json"));

        final JsonValue result = this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), Json.createValue("Retrospective studies"), node -> null);

        assertEquals(Json.createValue("Études rétrospectives"), result);
    }

    @Test
    void leavesUntranslatedValuesExactlyAsStored() throws Exception
    {
        // The ordinary case, and the reason the whole mechanism is additive: a half-translated deployment
        // renders in a mix of languages rather than showing holes where translations are missing.
        translations(Locale.FRENCH, Map.of("/somewhere/else", "Autre"));
        this.processor.start(resource(".localize:fr.json"));
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), stored, node -> null));
    }

    @Test
    void leavesValuesAloneWhenTheCatalogueEchoesMissingKeys() throws Exception
    {
        // Sling's own bundles answer a miss with the key itself rather than throwing. Allowing only for the
        // exception would replace every untranslated property with its own repository path — observed on a
        // running instance before this was handled.
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
        this.processor.start(resource(".localize:fr.json"));
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), stored, node -> null));
    }

    @Test
    void doesNothingAtAllWithoutALanguageSelector() throws Exception
    {
        translations(Locale.FRENCH, Map.of(TITLE_PATH, "Études rétrospectives"));
        this.processor.start(resource(".json"));
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), stored, node -> null));
    }

    @Test
    void leavesValuesThatAreNotTextAlone() throws Exception
    {
        translations(Locale.FRENCH, Map.of(TITLE_PATH, "Études rétrospectives"));
        this.processor.start(resource(".localize:fr.json"));
        final JsonValue number = Json.createValue(10);

        assertSame(number, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), number, node -> null));
    }

    @Test
    void leavesEverythingAloneWhenThereIsNoCatalogueForThatLanguage() throws Exception
    {
        when(this.bundles.getResourceBundle(any(), any())).thenReturn(null);
        this.processor.start(resource(".localize:fr.json"));
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), stored, node -> null));
    }

    @Test
    void forgetsTheLanguageOnceTheSerializationIsOver() throws Exception
    {
        // The thread goes back to a pool afterwards. A language left behind on it would be applied to
        // whatever request landed there next, which is the kind of fault that only shows under load.
        translations(Locale.FRENCH, Map.of(TITLE_PATH, "Études rétrospectives"));
        final Resource resource = resource(".localize:fr.json");
        this.processor.start(resource);
        this.processor.end(resource);
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), property(TITLE_PATH), stored, node -> null));
    }

    @Test
    void leavesAPropertyThatCannotSayWhereItLivesAlone() throws Exception
    {
        translations(Locale.FRENCH, Map.of(TITLE_PATH, "Études rétrospectives"));
        this.processor.start(resource(".localize:fr.json"));
        final Property broken = Mockito.mock(Property.class);
        when(broken.getPath()).thenThrow(new RepositoryException("no path"));
        final JsonValue stored = Json.createValue("Retrospective studies");

        assertSame(stored, this.processor.processProperty(
            Mockito.mock(Node.class), broken, stored, node -> null));
    }

    private Resource resource(final String resolutionPathInfo)
    {
        final Resource resource = this.context.create().resource("/Categories/Retrospective" + hashCode());
        resource.getResourceMetadata().setResolutionPathInfo(resolutionPathInfo);
        return resource;
    }

    private void translations(final Locale locale, final Map<String, String> entries)
    {
        when(this.bundles.getResourceBundle(eq(Messages.CONTENT), eq(locale))).thenReturn(bundleOf(entries));
    }

    private static Property property(final String path) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        when(property.getPath()).thenReturn(path);
        return property;
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
