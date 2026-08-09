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

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.i18n.api.Messages;

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
     * Where the i18n bundle's filter leaves the language a request asked for, whether it was named in the URL
     * or remembered from when it was.
     *
     * <p>Spelled out rather than imported: the name of a request attribute is not part of any interface, and
     * publishing one is how a filter talks to code it knows nothing about. How a request's language is
     * actually worked out lives in that bundle, once, and is asked for through {@link Messages} below.</p>
     */
    private static final String REQUEST_LOCALE = "io.uhndata.iap.i18n.locale";

    /** Where that same filter leaves the direction the language reads in. */
    private static final String DIRECTION = "io.uhndata.iap.i18n.direction";

    @SlingObject
    private ResourceResolver resourceResolver;

    /** Present when adapted from a request, which is the only case that has a reader with a language. */
    @SlingObject
    private SlingJakartaHttpServletRequest request;

    /** Absent until the i18n bundle is up, which is later than this one. */
    @OSGiService
    private Messages messages;

    private Map<String, String> properties;

    @PostConstruct
    protected void init()
    {
        this.properties = new LinkedHashMap<>();
        final Resource root = this.resourceResolver.getResource(CONF_ROOT);
        if (root != null) {
            collect(root, this.properties, readerLocale());
        }
    }

    /**
     * The language this page is being rendered in, as a tag for {@code <html lang>}.
     *
     * <p>Rendered by the server rather than set by a script, because a screen reader chooses its voice and
     * its pronunciation rules from this attribute while the page is being parsed — by the time a script could
     * correct it, the page has already been announced in the wrong accent.</p>
     *
     * @return a language tag, or the empty string where there is no reader to have one
     */
    @NotNull
    public String getLanguage()
    {
        final Object asked = this.request == null ? null : this.request.getAttribute(REQUEST_LOCALE);
        return asked instanceof Locale named ? named.toLanguageTag() : "";
    }

    /**
     * Which way this page reads, as {@code ltr} or {@code rtl} for {@code <html dir>}.
     *
     * <p>Also the server's job, and for a second reason beyond the first paint: the theme is built when the
     * frontend bundle loads, and anything asking it which way round the page is has to be able to get a
     * truthful answer then. A direction applied afterwards would leave every such question answered "ltr"
     * for the life of the page.</p>
     *
     * @return {@code rtl} or {@code ltr}, defaulting to {@code ltr} where nothing said
     */
    @NotNull
    public String getDirection()
    {
        final Object direction = this.request == null ? null : this.request.getAttribute(DIRECTION);
        return "rtl".equals(direction) ? "rtl" : "ltr";
    }

    /**
     * The language to render this configuration in, where there is a reader to render it for.
     *
     * <p>Adapted from a plain resource — from a background job, say — there is no reader and therefore no
     * language, so the configuration is returned exactly as stored.</p>
     *
     * <p>A language the request asked for wins over the one the browser merely announced. Somebody who has
     * landed on a page in a language they do not read has to be able to say so, and changing a browser's
     * language preference to read one page is not something to ask of a visitor — the reader who most needs
     * the escape is the one least able to follow instructions written in a language they cannot read.</p>
     *
     * @return the reader's language, or {@code null} to leave everything as stored
     */
    private Locale readerLocale()
    {
        if (this.messages == null || this.request == null) {
            return null;
        }
        final Object asked = this.request.getAttribute(REQUEST_LOCALE);
        return asked instanceof Locale named ? named : this.request.getLocale();
    }

    private void collect(final Resource resource, final Map<String, String> out, final Locale locale)
    {
        final ValueMap values = resource.getValueMap();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            final String name = entry.getKey();
            if (name.startsWith("jcr:")) {
                continue;
            }
            final String value = values.get(name, String.class);
            if (StringUtils.isNotBlank(value)) {
                out.put(name, translate(resource, name, value, locale));
            }
        }
        for (Resource child : resource.getChildren()) {
            collect(child, out, locale);
        }
    }

    /**
     * One property in the reader's language, where a translation for it exists.
     *
     * <p>Keyed by the property's own path, so a translation is added without touching the content it
     * translates and the shipped text goes on being its own fallback. Configuration nobody has catalogued
     * comes back exactly as stored — which is most of it, since this tree holds a version number and a list
     * of language codes alongside the two paragraphs anybody reads.</p>
     *
     * @param resource the config node the property belongs to
     * @param name the property name
     * @param value the value as stored
     * @param locale the reader's language, or {@code null} where there is no reader
     * @return the translated value, or the stored one
     */
    private String translate(final Resource resource, final String name, final String value, final Locale locale)
    {
        if (locale == null) {
            return value;
        }
        return this.messages.translate(Messages.CONTENT, resource.getPath() + "/" + name, locale, value);
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
