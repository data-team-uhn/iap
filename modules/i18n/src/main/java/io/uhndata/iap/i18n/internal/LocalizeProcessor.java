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
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.i18n.api.Messages;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.SelectorUtils;

/**
 * Replaces content with its translation, in place.
 *
 * <p>In place, and deliberately: a translated title arrives under {@code title}, where the untranslated one
 * used to be. Every consumer that already reads the property keeps working and gets the reader's language for
 * free, which adding a second key beside it would not achieve — each consumer would have to be taught to
 * prefer it. This is the same shape {@code dereference} already uses, for the same reason.</p>
 *
 * <p>Translations are keyed by the <em>path of the property</em> they translate, so nothing has to be stored
 * beside the content itself and shipped English stays readable as its own fallback. Where no translation
 * exists the stored value simply stands, which is what makes the whole mechanism additive: a half-translated
 * deployment renders, rather than showing holes.</p>
 *
 * <p>The language comes from a selector, {@code .localize:fr.json}, because the serializer is driven by a
 * resource and its selectors and never sees a request — there is no "the current user's language" to read
 * here. Callers that do have a request are the ones that know it. Without such a selector this does nothing
 * at all, so an untranslated deployment pays only a check per property; {@code -localize} switches it off
 * outright, which is how an editor asks for the source text rather than the reader's version.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ResourceJsonProcessor.class)
public class LocalizeProcessor implements ResourceJsonProcessor
{
    /** The selector prefix carrying the language to answer in, e.g. {@code .localize:fr-CA.json}. */
    private static final String NAME = "localize";

    /**
     * The language this thread is serializing into.
     *
     * <p>Thread-local rather than a field because a processor is a single OSGi service shared by every
     * request, so a field would let one request's language leak into another's response. The serializer
     * brackets each serialization with {@code start} and {@code end} on the thread performing it, and
     * recurses internally rather than re-entering, so exactly one language is in play per thread.</p>
     */
    private final ThreadLocal<Locale> locale = new ThreadLocal<>();

    @Reference
    private ResourceBundleProvider bundles;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public int getPriority()
    {
        // After the processors that decide what a property's value is at all: this only rewrites the text of
        // one already settled.
        return 20;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        return true;
    }

    @Override
    public void start(final Resource resource)
    {
        final Map<String, String> requested =
            SelectorUtils.parseOptionsToMap(NAME, resource.getResourceMetadata().getResolutionPathInfo());
        requested.keySet().stream().findFirst()
            .map(Locale::forLanguageTag)
            .ifPresent(this.locale::set);
    }

    @Override
    public void end(final Resource resource)
    {
        // Removed rather than set to null: the thread goes back to a pool, and a language left behind on it
        // would be picked up by whatever request lands there next.
        this.locale.remove();
    }

    @Override
    public JsonValue processProperty(final Node node, final Property property, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        final Locale requested = this.locale.get();
        if (requested == null || !(input instanceof JsonString)) {
            // No language asked for, or nothing a translation could replace
            return input;
        }
        final ResourceBundle bundle = this.bundles.getResourceBundle(Messages.CONTENT, requested);
        if (bundle == null) {
            return input;
        }
        try {
            return Json.createValue(bundle.getString(pathOf(property)));
        } catch (final MissingResourceException e) {
            // Untranslated, which is the ordinary case for most properties: the stored value stands
            return input;
        }
    }

    private static String pathOf(final Property property)
    {
        try {
            return property.getPath();
        } catch (final RepositoryException e) {
            // A property that cannot say where it lives cannot be looked up; leaving it alone is the only
            // sensible answer, and matching nothing is exactly what an unfindable key does anyway.
            return "";
        }
    }
}
