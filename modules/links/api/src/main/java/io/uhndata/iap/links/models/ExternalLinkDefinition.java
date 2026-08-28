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
 * A Sling Model wrapping an {@code link:ExternalDefinition} node: a type of link recording a value that means
 * something outside this repository — an identifier in another system — instantiated as {@link ExternalLink}s.
 * There is no target the repository can resolve, so what a definition says here is about the value itself: what
 * a valid one looks like, and how to turn it into an address somebody can follow.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ExternalLinkDefinition.RESOURCE_TYPE,
    adapters = { ExternalLinkDefinition.class, LinkDefinition.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ExternalLinkDefinition extends LinkDefinition
{
    /** The {@code sling:resourceType} of an {@code link:ExternalDefinition} node. */
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
     * An optional regular expression that recorded values must fully match.
     *
     * @return a regular expression, or {@code null} if values are unrestricted
     */
    @Nullable
    public String getValuePattern()
    {
        return this.valuePattern;
    }

    /**
     * An optional URL template turning the recorded value into a navigable address, with {@code {value}}
     * standing for the recorded value.
     *
     * @return a template, or {@code null} if values are not navigable
     */
    @Nullable
    public String getUrlTemplate()
    {
        return this.urlTemplate;
    }

    /**
     * Whether a value may be recorded as a link of this type. The rule belongs to the definition rather than to
     * whoever is creating the link, so that every route into the repository applies the same one.
     *
     * @param value the value a caller wants to record
     * @return {@code true} if the type places no restriction, or the value satisfies it
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
