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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A link to something outside this repository: a {@code link:ExternalLink} node, recording a value that names the
 * target, such as this resource's identifier in another system.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Link.class, resourceType = ExternalLink.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ExternalLink extends Link
{
    /** The resource type of {@code link:ExternalLink}. */
    public static final String RESOURCE_TYPE = "link/ExternalLink";

    /** The property that holds the recorded value. */
    public static final String VALUE_PROPERTY = "value";

    @ValueMapValue(name = VALUE_PROPERTY)
    private String value;

    /**
     * The recorded value, which names the target, such as an identifier in another system.
     *
     * @return the value
     */
    @NotNull
    public String getValue()
    {
        return this.value;
    }

    /**
     * The definition of this link's type, an external one.
     *
     * @return the definition; {@code null} if this session cannot read it
     */
    @Override
    @Nullable
    public ExternalLinkDefinition getDefinition()
    {
        return this.getDefinition(ExternalLinkDefinition.class);
    }

    /**
     * The address of the target: the definition's {@link ExternalLinkDefinition#getUrlTemplate() URL template},
     * with the recorded value in place of {@code {value}}. The value goes in as it is, without encoding.
     *
     * @return the address; {@code null} if this session cannot read the definition, or it sets no URL template
     */
    @Nullable
    public String getTargetUrl()
    {
        final ExternalLinkDefinition definition = this.getDefinition();
        final String template = definition == null ? null : definition.getUrlTemplate();
        if (template == null || this.value == null) {
            return null;
        }
        return template.replace("{value}", this.value);
    }

    @Override
    protected String getDefaultTargetLabel()
    {
        return this.value == null ? "" : this.value;
    }

    @Override
    protected String resolveTargetPlaceholder(final String name)
    {
        return "value".equals(name) && this.value != null ? this.value : "";
    }
}
