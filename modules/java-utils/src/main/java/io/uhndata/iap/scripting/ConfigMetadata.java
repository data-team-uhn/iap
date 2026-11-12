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
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.jetbrains.annotations.NotNull;

/**
 * The platform configuration a page exposes, such as its title and its {@code <meta>} tags.
 *
 * <p>
 * It collects the properties of every {@code app/Configuration} resource under {@value #CONF_ROOT} into one flat map.
 * A property with a namespace, or a blank value, is left out. Where two resources set the same property, the one
 * read last wins. Any resource adapts to it, whatever its path. To use it from HTL:
 * </p>
 *
 * {@snippet lang=html :
 * <sly data-sly-use.config="io.uhndata.iap.scripting.ConfigMetadata">
 *   <sly data-sly-repeat="${config.properties.entrySet.iterator}">
 *     <meta name="${item.key}" content="${item.value}">
 *   </sly>
 * </sly>
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConfigMetadata
{
    /** The path under which the configuration resources are collected. */
    public static final String CONF_ROOT = "/libs/iap/conf";

    /** The resource type of a configuration resource, the one {@code app:Configuration} nodes have. */
    public static final String CONF_RESOURCE_TYPE = "app/Configuration";

    @SlingObject
    private ResourceResolver resourceResolver;

    private Map<String, String> properties;

    @PostConstruct
    protected void init()
    {
        this.properties = new LinkedHashMap<>();
        final Resource root = this.resourceResolver.getResource(CONF_ROOT);
        if (root != null) {
            collect(root, this.properties);
        }
    }

    private void collect(final Resource resource, final Map<String, String> out)
    {
        if (resource.isResourceType(CONF_RESOURCE_TYPE)) {
            final ValueMap values = resource.getValueMap();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                final String name = entry.getKey();
                // A configuration property has no namespace. This leaves out jcr:created, and the sling:resourceType
                // and sling:resourceSuperType that every app:Configuration has.
                if (name.indexOf(':') >= 0) {
                    continue;
                }
                final String value = values.get(name, String.class);
                if (StringUtils.isNotBlank(value)) {
                    out.put(name, value);
                }
            }
        }
        for (Resource child : resource.getChildren()) {
            collect(child, out);
        }
    }

    /**
     * The collected configuration.
     *
     * @return each property's name, mapped to its value as a string; empty if there is no configuration
     */
    @NotNull
    public Map<String, String> getProperties()
    {
        return Collections.unmodifiableMap(this.properties);
    }
}
