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
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping the {@code iap:LinkTypesHomepage} node at {@code /LinkTypes}, holding the link
 * definitions.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LinkTypesHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LinkTypesHomepage extends Content
{
    /** The {@code sling:resourceType} of the {@code iap:LinkTypesHomepage} node. */
    public static final String RESOURCE_TYPE = "iap/LinkTypesHomepage";

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
}
