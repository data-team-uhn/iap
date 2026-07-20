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

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
