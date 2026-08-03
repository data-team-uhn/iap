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
package io.uhndata.iap.links.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping the {@code iap:LinkTypesHomepage} node at {@code /LinkTypes}, holding the link
 * definitions. The homepage documents the whole link vocabulary: the node carries the {@code iap:Documented} mixin
 * through its primary type, so the catalogue of defined link types is served at {@code /LinkTypes.doc.json} and
 * {@code /LinkTypes.doc.md}. Its heading comes from the {@code title} and {@code description} properties,
 * autocreated from the defaults declared by the {@code iap:LinkTypesHomepage} node type and editable by a
 * deployment wanting to reword it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { LinkTypesHomepage.class, AutoDocumentable.class },
    resourceType = LinkTypesHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LinkTypesHomepage extends Content implements AutoDocumentable
{
    /** The {@code sling:resourceType} of the {@code iap:LinkTypesHomepage} node. */
    public static final String RESOURCE_TYPE = "iap/LinkTypesHomepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * All the defined link types.
     *
     * @return a list of link definitions, empty if none are defined
     */
    @NotNull
    public List<LinkDefinition> getDefinitions()
    {
        return this.getChildren(LinkDefinition.RESOURCE_TYPE, LinkDefinition.class);
    }

    @Override
    @NotNull
    public String getDocumentationTitle()
    {
        return this.title;
    }

    @Override
    @Nullable
    public String getDocumentationIntro()
    {
        return this.description;
    }

    @Override
    @NotNull
    public List<LinkDefinition> getDocumentedItems()
    {
        return getDefinitions();
    }
}
