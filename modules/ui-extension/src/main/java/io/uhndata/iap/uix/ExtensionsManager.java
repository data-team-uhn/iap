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
import java.util.stream.Collectors;

import javax.jcr.RepositoryException;
import javax.script.Bindings;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * The extensions registered on one extension point, for an HTL script. The script names the point by its
 * {@code ext:pointId}, in the {@code uixp} option, and gets the extensions in their {@code defaultOrder}:
 *
 * {@snippet lang=html :
 * <input data-sly-use.em="${'io.uhndata.iap.uix.ExtensionsManager' @ uixp='iap/coreUI/view'}"
 *   type="hidden" id="SomeIdentifier" value="${em.enabled}" />
 * }
 *
 * <p>
 * The script can also list the extensions as resources, instead of as JSON:
 * </p>
 *
 * {@snippet lang=html :
 *   <ul data-sly-use.em="${'io.uhndata.iap.uix.ExtensionsManager' @ uixp='iap/coreUI/view'}">
 *     <li data-sly-repeat="${em.listAll}">${item.name}</li>
 *   </ul>
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ExtensionsManager implements Use
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionsManager.class);

    private final List<Resource> matchingExtensions = new ArrayList<>();

    private ResourceResolver resourceResolver;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        final String uixp = (String) bindings.get("uixp");
        if (StringUtils.isBlank(uixp)) {
            LOGGER.warn("Invalid usage of the extension manager: required parameter [uixp] missing");
            return;
        }

        this.resourceResolver = (ResourceResolver) bindings.get("resolver");

        try {
            findExtensions(uixp);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while querying extensions: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ExtensionsManager.class, "findExtensions")
                .with("extensionPoint", uixp));
        }
    }

    /**
     * Finds the extensions registered on an extension point, in their {@code defaultOrder}, and keeps them in
     * {@link #matchingExtensions}.
     *
     * @param extensionPointId the {@code ext:pointId} of the extension point, such as {@code iap/coreUI/view}
     */
    private void findExtensions(final String extensionPointId) throws RepositoryException
    {
        LOGGER.debug("Looking for extensions for [{}]", extensionPointId);
        final Iterator<Resource> result = this.resourceResolver.findResources(
            "select n from [ext:Extension] as n where n.'ext:pointId' = '" + extensionPointId
            + "' order by n.'defaultOrder' OPTION (index tag property)",
            "JCR-SQL2");
        result.forEachRemaining(extension -> this.matchingExtensions.add(extension));
        LOGGER.debug("Found [{}] extensions", this.matchingExtensions.size());
    }

    /**
     * All the extensions of the point as a JSON array, the disabled ones included. The extensions to show are
     * {@link #getEnabled()}.
     *
     * @return a JSON array with one object per extension; empty if none were found
     * @see #getEnabled()
     */
    @NotNull
    public String getAll()
    {
        return toString(listAll());
    }

    /**
     * All the extensions of the point, the disabled ones included. The extensions to show are
     * {@link #listEnabled()}.
     *
     * @return the extension resources, in a list that cannot be modified; empty if none were found
     * @see #listEnabled()
     */
    @NotNull
    public List<Resource> listAll()
    {
        return Collections.unmodifiableList(this.matchingExtensions);
    }

    /**
     * The extensions of the point that {@code ext:defaultDisabled} does not disable, as a JSON array.
     *
     * @return a JSON array with one object per extension; empty if none is enabled
     */
    @NotNull
    public String getEnabled()
    {
        return toString(listEnabled());
    }

    /**
     * The extensions of the point that {@code ext:defaultDisabled} does not disable.
     *
     * @return the extension resources; empty if none is enabled
     */
    @NotNull
    public List<Resource> listEnabled()
    {
        return this.matchingExtensions.stream()
            .filter(i -> {
                Boolean b = i.getValueMap().get("ext:defaultDisabled", Boolean.class);
                return b == null ? true : !b.booleanValue();
            })
            .collect(Collectors.toList());
    }

    /**
     * Serializes extensions as a JSON array. An extension that cannot be serialized is logged and left out.
     *
     * @param extensions the extension resources to serialize
     * @return the text of a JSON array, with one object per extension
     */
    private String toString(final List<Resource> extensions)
    {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        extensions.stream().forEach(extension -> {
            final JsonObject json = extension.adaptTo(JsonObject.class);
            if (json == null) {
                LOGGER.warn("Could not serialize extension [{}] to JSON, skipping it", extension.getPath());
                ErrorLogger.logProblem("extension could not be serialized",
                    ErrorContext.of(ExtensionsManager.class, "toString").about(extension));
            } else {
                builder.add(json);
            }
        });
        return builder.build().toString();
    }
}
