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
package io.uhndata.iap.profiles.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping the {@code profile:FieldsHomepage} node, the root container holding the profile field
 * definitions. The homepage documents the whole catalogue: the node carries the {@code doc:Documented} mixin through
 * its primary type, so the catalogue is served at {@code /ProfileFields.doc.json} and {@code /ProfileFields.doc.md}
 * without any code of its own. Its heading comes from the {@code title} and {@code description} properties,
 * autocreated from the defaults declared by the node type and editable by a deployment wanting to reword it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { ProfileFieldsHomepage.class, AutoDocumentable.class },
    resourceType = ProfileFieldsHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ProfileFieldsHomepage extends Content implements AutoDocumentable
{
    /** The {@code sling:resourceType} of an {@code profile:FieldsHomepage} node. */
    public static final String RESOURCE_TYPE = "profile/FieldsHomepage";

    /** The repository path this homepage lives at. */
    public static final String PATH = "/ProfileFields";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The catalogue, read once. A model is a view of the repository as one resolver sees it at one moment, and the
     * profile API asks it for one definition after another while serving a single request; reading, adapting and
     * sorting every child again for each of those would be work done over and over for the same answer.
     */
    private List<ProfileFieldDefinition> definitions;

    /**
     * All the field definitions held in this homepage, in display order.
     *
     * @return the field definitions, an empty list if there are none
     */
    @NotNull
    public List<ProfileFieldDefinition> getDefinitions()
    {
        if (this.definitions == null) {
            final List<ProfileFieldDefinition> read =
                new ArrayList<>(getChildren(ProfileFieldDefinition.RESOURCE_TYPE, ProfileFieldDefinition.class));
            read.sort(ProfileFieldDefinition.DISPLAY_ORDER);
            this.definitions = List.copyOf(read);
        }
        return this.definitions;
    }

    /**
     * Looks up one definition by the name the profile API knows it as.
     *
     * @param fieldName the field name to look for
     * @return the matching definition, or empty when this instance records no such thing
     */
    @NotNull
    public Optional<ProfileFieldDefinition> getDefinition(@NotNull final String fieldName)
    {
        return getDefinitions().stream().filter(definition -> fieldName.equals(definition.getName())).findFirst();
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
    public List<ProfileFieldDefinition> getDocumentedItems()
    {
        return getDefinitions();
    }
}
