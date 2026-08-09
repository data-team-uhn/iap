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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.jetbrains.annotations.NotNull;

/**
 * A Sling Model that gathers all the metadata to be exposed as {@code <meta>} tags in the HTML source.
 *
 * <p>
 * This automatically collects every property, from every node in the {@code /libs/iap/conf} tree, into a single flat
 * map. Properties in the {@code jcr:} namespace, and blank properties, are skipped. As a Sling Model, it can be adapted
 * from any {@code Resource}, in HTL as well as in Java or ESP code. For example, to use from HTL:
 * </p>
 *
 * <p>
 * <code>
 * &lt;sly data-sly-use.config="io.uhndata.iap.scripting.ConfigMetadata"&gt;
 *   &lt;sly data-sly-repeat="${config.properties.entrySet.iterator}"&gt;
 *     &lt;meta name="${item.key}" content="${item.value}"&gt;
 *   &lt;/sly&gt;
 * &lt;/sly&gt;
 * </code>
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = { Resource.class, SlingJakartaHttpServletRequest.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConfigMetadata
{
    /** The JCR node under which all the config nodes to be collected live. */
    public static final String CONF_ROOT = "/libs/iap/conf";

    /**
     * The catalog holding translations of shipped content, keyed by the path of the property each one
     * translates.
     *
     * <p>Spelled out rather than imported from the i18n module, which cannot be depended on from here: this
     * bundle starts at the same order, and the feature analyser requires a package's exporter to start no
     * later than its importer. One duplicated string is the smaller price.</p>
     */
    private static final String CONTENT_CATALOG = "iap.content";

    /** The request parameter naming a language outright, overriding whatever the browser announced. */
    private static final String LOCALE = "locale";

    @SlingObject
    private ResourceResolver resourceResolver;

    /** Present when adapted from a request, which is the only case that has a reader with a language. */
    @SlingObject
    private SlingJakartaHttpServletRequest request;

    /** Absent until the i18n bundle is up, which is later than this one. */
    @OSGiService
    private ResourceBundleProvider bundles;

    private Map<String, String> properties;

    @PostConstruct
    protected void init()
    {
        this.properties = new LinkedHashMap<>();
        final Resource root = this.resourceResolver.getResource(CONF_ROOT);
        if (root != null) {
            collect(root, this.properties, translations());
        }
    }

    /**
     * The translations to apply, if there are any to apply and anybody to apply them for.
     *
     * <p>Adapted from a plain resource — from a background job, say — there is no reader and therefore no
     * language, so the configuration is returned exactly as stored.</p>
     *
     * <p>A named language wins over the browser's own. This page is where somebody who has landed in the
     * wrong language has to be able to say so, and they cannot: changing a browser's language preference to
     * read one page is not something to ask of a visitor, and the reader who most needs the escape is the one
     * least able to follow instructions written in a language they do not read.</p>
     *
     * @return the content catalog in the reader's language, or {@code null} to leave everything as stored
     */
    private ResourceBundle translations()
    {
        if (this.bundles == null || this.request == null) {
            return null;
        }
        final String named = this.request.getParameter(LOCALE);
        final Locale locale = named == null || named.isBlank()
            ? this.request.getLocale() : Locale.forLanguageTag(named);
        return locale == null ? null : this.bundles.getResourceBundle(CONTENT_CATALOG, locale);
    }

    private void collect(final Resource resource, final Map<String, String> out, final ResourceBundle translations)
    {
        final ValueMap values = resource.getValueMap();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            final String name = entry.getKey();
            if (name.startsWith("jcr:")) {
                continue;
            }
            final String value = values.get(name, String.class);
            if (StringUtils.isNotBlank(value)) {
                out.put(name, translate(resource, name, value, translations));
            }
        }
        for (Resource child : resource.getChildren()) {
            collect(child, out, translations);
        }
    }

    /**
     * One property in the reader's language, where a translation for it exists.
     *
     * <p>Keyed by the property's own path, so a translation is added without touching the content it
     * translates and the shipped English goes on being its own fallback.</p>
     *
     * @param resource the config node the property belongs to
     * @param name the property name
     * @param value the value as stored
     * @param translations the catalog, or {@code null} when there is nothing to apply
     * @return the translated value, or the stored one
     */
    private String translate(final Resource resource, final String name, final String value,
        final ResourceBundle translations)
    {
        if (translations == null) {
            return value;
        }
        final String key = resource.getPath() + "/" + name;
        try {
            final String translated = translations.getString(key);
            // Sling's own bundles answer a miss with the key itself rather than throwing, so the exception
            // below is not enough on its own: without this check every untranslated property would be
            // replaced by its own path. Found by rendering the page in English and reading it back.
            return translated == null || translated.equals(key) ? value : translated;
        } catch (final MissingResourceException e) {
            // A bundle that does throw, which the specification allows and some implementations do
            return value;
        }
    }

    /**
     * The collected properties, flattened from every node under {@link #CONF_ROOT}.
     *
     * @return a map of property name to property value
     */
    @NotNull
    public Map<String, String> getProperties()
    {
        return Collections.unmodifiableMap(this.properties);
    }
}
