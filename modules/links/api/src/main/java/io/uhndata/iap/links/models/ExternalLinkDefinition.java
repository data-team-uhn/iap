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

import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A type of link that records a value naming something outside this repository, such as an identifier in another
 * system. It is defined by a {@code link:ExternalDefinition} node, and its links are {@link ExternalLink}s. It says
 * what a valid value looks like, and how to turn one into an address.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ExternalLinkDefinition.RESOURCE_TYPE,
    adapters = { ExternalLinkDefinition.class, LinkDefinition.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ExternalLinkDefinition extends LinkDefinition
{
    /** The resource type of {@code link:ExternalDefinition}. */
    public static final String RESOURCE_TYPE = "link/ExternalDefinition";

    @ValueMapValue
    private String valuePattern;

    @ValueMapValue
    private String urlTemplate;

    @Override
    @NotNull
    protected String getKind()
    {
        return "external";
    }

    /**
     * A regular expression every recorded value must match in full.
     *
     * @return the expression; {@code null} if any value is accepted
     */
    @Nullable
    public String getValuePattern()
    {
        return this.valuePattern;
    }

    /**
     * A template that turns a recorded value into an address, with {@code {value}} standing for the value, such as
     * {@code https://ehr.example.org/chart/{value}}.
     *
     * @return the template; {@code null} if the values have no address
     */
    @Nullable
    public String getUrlTemplate()
    {
        return this.urlTemplate;
    }

    /**
     * Whether a value may be recorded as a link of this type.
     *
     * @param value the value to record
     * @return {@code true} if the definition sets no value pattern, or the value matches it in full
     */
    public boolean accepts(@Nullable final String value)
    {
        return this.valuePattern == null || value != null && value.matches(this.valuePattern);
    }

    @Override
    protected void addBehaviorDetails(@NotNull final List<String> details)
    {
        details.add("**External**: records a value pointing outside the repository");
    }

    @Override
    protected void addRestrictionDetails(@NotNull final List<String> details)
    {
        super.addRestrictionDetails(details);
        if (this.valuePattern != null) {
            details.add("**Value pattern**: `" + this.valuePattern + "`");
        }
        if (this.urlTemplate != null) {
            details.add("**URL template**: `" + this.urlTemplate + "`");
        }
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = super.documentationJsonBuilder();
        if (this.valuePattern != null) {
            json.add("valuePattern", this.valuePattern);
        }
        if (this.urlTemplate != null) {
            json.add("urlTemplate", this.urlTemplate);
        }
        return json;
    }
}
