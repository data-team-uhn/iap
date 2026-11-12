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
package io.uhndata.iap.tags.models;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.content.models.Content;

/**
 * The {@code /Tags} homepage, which holds the tag definitions and serves their catalogue at {@code /Tags.doc.md}
 * and {@code /Tags.doc.json}. The catalogue's heading comes from the {@code title} and {@code description}
 * properties, which the {@code tag:Homepage} node type autocreates and a deployment can edit.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { TagsHomepage.class, AutoDocumentable.class },
    resourceType = TagsHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TagsHomepage extends Content implements AutoDocumentable
{
    /** The {@code sling:resourceType} of a {@code tag:Homepage} node. */
    public static final String RESOURCE_TYPE = "tag/Homepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * All the tag definitions under this homepage, in display order.
     *
     * @return the tag definitions, an empty list if there are none
     */
    @NotNull
    public List<TagDefinition> getDefinitions()
    {
        final List<TagDefinition> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (!child.isResourceType(TagDefinition.RESOURCE_TYPE)) {
                continue;
            }
            final TagDefinition definition = child.adaptTo(TagDefinition.class);
            if (definition != null) {
                result.add(definition);
            }
        }
        result.sort(TagDefinition.DISPLAY_ORDER);
        return result;
    }

    @Override
    @NotNull
    public String getDocumentationTitle()
    {
        return this.title;
    }

    @Override
    @NotNull
    public String getDocumentationIntro()
    {
        return this.description;
    }

    @Override
    @NotNull
    public List<TagDefinition> getDocumentedItems()
    {
        return getDefinitions();
    }
}
