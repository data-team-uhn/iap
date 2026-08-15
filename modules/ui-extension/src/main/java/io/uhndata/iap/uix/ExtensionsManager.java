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
package io.uhndata.iap.uix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.jcr.RepositoryException;
import javax.script.Bindings;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.i18n.api.Locales;
import io.uhndata.iap.i18n.api.Messages;

/**
 * A HTL Use-API that lists UI Extensions. To use this API, simply place the following code in a HTL file:
 *
 * <p>
 * <code>
 * &lt;input data-sly-use.em="${'io.uhndata.iap.uix.ExtensionsManager' @ uixp='ExtensionPointName'}"
 *   type="hidden" id="SomeIdentifier" value="${em.enabled}" /&gt;
 * </code>
 * </p>
 *
 * <p>
 * Another way, using the resources themselves instead of the JSON serialization:
 * </p>
 *
 * <p>
 * <code>
 *   &lt;ul data-sly-use.em="${'io.uhndata.iap.uix.ExtensionsManager' @ uixp='ExtensionPointName'}"&gt;
 *     &lt;li data-sly-repeat="${em.listAll}"&gt;${item.name}&lt;/li&gt;
 *   &lt;/ul&gt;
 * </code>
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ExtensionsManager implements Use
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionsManager.class);

    private final List<Resource> matchingExtensions = new ArrayList<>();

    private ResourceResolver resourceResolver;

    /** Absent where the i18n bundle is not up yet, in which case extensions are listed as they were written. */
    private Messages messages;

    private Locales locales;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        final String uixp = (String) bindings.get("uixp");
        if (StringUtils.isBlank(uixp)) {
            LOGGER.warn("Invalid usage of the extension manager: required parameter [uixp] missing");
            return;
        }

        this.resourceResolver = (ResourceResolver) bindings.get("resolver");
        final SlingScriptHelper sling = (SlingScriptHelper) bindings.get("sling");
        if (sling != null) {
            this.messages = sling.getService(Messages.class);
            this.locales = sling.getService(Locales.class);
        }

        try {
            findExtensions(uixp);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while querying extensions: {}", e.getMessage(), e);
        }
    }

    /**
     * Finds all the extensions for the given extension point and collects them in {@link #matchingExtensions}.
     *
     * @param extensionPointId the identifier of an extension point
     */
    private void findExtensions(final String extensionPointId) throws RepositoryException
    {
        LOGGER.debug("Looking for extensions for [{}]", extensionPointId);
        final Iterator<Resource> result = this.resourceResolver.findResources(
            "select n from [iap:Extension] as n where n.'iap:extensionPointId' = '" + extensionPointId
            + "' order by n.'iap:defaultOrder' OPTION (index tag property)",
            "JCR-SQL2");
        result.forEachRemaining(extension -> this.matchingExtensions.add(extension));
        LOGGER.debug("Found [{}] extensions", this.matchingExtensions.size());
    }

    /**
     * Gets all the matching extensions as a serialized JSON array. The extensions are ordered in the preferred display
     * order. This includes disabled extensions, so it should not be used for actually listing the extensions to be
     * displayed, see {@link #getEnabled()}.
     *
     * @return a JsonArray with all the matching extensions
     * @see #getEnabled()
     */
    @NotNull
    public String getAll()
    {
        return toString(listAll());
    }

    /**
     * Gets all the matching extensions. The extensions are ordered in the preferred display order. This includes
     * disabled extensions, so it should not be used for actually listing the extensions to be displayed, see
     * {@link #getEnabled()}.
     *
     * @return a list of all the matching extensions
     * @see #getEnabled()
     */
    @NotNull
    public List<Resource> listAll()
    {
        return Collections.unmodifiableList(this.matchingExtensions);
    }

    /**
     * Gets the non-disabled matching extensions as a serialized JSON array. The extensions are ordered in the preferred
     * display order.
     *
     * @return a JsonArray with the enabled matching extensions
     */
    @NotNull
    public String getEnabled()
    {
        return toString(listEnabled());
    }

    /**
     * Gets the non-disabled matching extensions. The extensions are ordered in the preferred display order.
     *
     * @return a list of the enabled matching extensions
     */
    @NotNull
    public List<Resource> listEnabled()
    {
        return this.matchingExtensions.stream()
            .filter(i -> {
                Boolean b = i.getValueMap().get("iap:defaultDisabled", Boolean.class);
                return b == null ? true : !b.booleanValue();
            })
            .collect(Collectors.toList());
    }

    /**
     * Serializes a list of extension resources as a JSON Array.
     *
     * @param extensions the extensions to serialize
     * @return a string representing a JSON Array, with each of the passed extensions in turn serialized as a JSON
     *         Object
     */
    private String toString(final List<Resource> extensions)
    {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        extensions.stream().forEach(extension -> {
            final JsonObject json = extension.adaptTo(JsonObject.class);
            if (json == null) {
                // A failed serialization of one extension shouldn't take down the whole extension point
                LOGGER.warn("Could not serialize extension [{}] to JSON, skipping it", extension.getPath());
            } else {
                builder.add(inReadersLanguage(extension, json));
            }
        });
        return builder.build().toString();
    }

    /**
     * One extension with its text in the language the page is being rendered in.
     *
     * <p>Translated here rather than by the serializer's localize processor, which is driven by a selector on
     * the request and never sees one: these resources are found by a query and adapted directly, so there is
     * no request path carrying a language for that processor to read. The label an extension displays is
     * exactly the kind of text a deployment rewrites, so leaving it untranslatable would make the footer of
     * every page the one part of it stuck in English.</p>
     *
     * <p>Keyed by the path of each property, like every other translation of shipped content: a property with
     * no entry in the catalog comes back exactly as it was, which is what {@code iap:targetURL} and
     * {@code iap:extensionPointId} need — most of what an extension carries is machinery rather than
     * words.</p>
     *
     * @param extension the extension being listed
     * @param json its properties, as serialized
     * @return the same properties, with the ones somebody has translated replaced
     */
    private JsonObject inReadersLanguage(final Resource extension, final JsonObject json)
    {
        if (this.messages == null || this.locales == null) {
            return json;
        }
        final Locale locale = this.locales.getReaderLocale();
        final JsonObjectBuilder translated = Json.createObjectBuilder();
        json.forEach((name, value) -> {
            if (value.getValueType() == JsonValue.ValueType.STRING) {
                translated.add(name, this.messages.translate(Messages.CONTENT,
                    extension.getPath() + "/" + name, locale, ((JsonString) value).getString()));
            } else {
                translated.add(name, value);
            }
        });
        return translated.build();
    }
}
