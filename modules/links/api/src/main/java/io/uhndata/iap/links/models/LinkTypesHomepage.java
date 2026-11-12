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
 * The homepage at {@code /LinkTypes}, which holds the link definitions. It serves the catalogue of link types at
 * {@code /LinkTypes.doc.json} and {@code /LinkTypes.doc.md}. The catalogue's title and introduction are the node's
 * {@code title} and {@code description}, which a deployment can edit.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { LinkTypesHomepage.class, AutoDocumentable.class },
    resourceType = LinkTypesHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LinkTypesHomepage extends Content implements AutoDocumentable
{
    /** The resource type of {@code link:TypesHomepage}. */
    public static final String RESOURCE_TYPE = "link/TypesHomepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * All the link definitions.
     *
     * @return the definitions, in stored order; empty if none are defined
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
