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
 * A Sling Model wrapping an {@code iap:ExternalLink} node: a link to something outside the repository, recording
 * the target as a value — e.g. this resource's identifier in an external system.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Link.class, resourceType = ExternalLink.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ExternalLink extends Link
{
    /** The {@code sling:resourceType} of an {@code iap:ExternalLink} node. */
    public static final String RESOURCE_TYPE = "iap/ExternalLink";

    /** The name of the property holding the recorded value. */
    public static final String VALUE_PROPERTY = "value";

    @ValueMapValue(name = VALUE_PROPERTY)
    private String value;

    /**
     * The recorded value identifying the external target, e.g. an identifier in an external system.
     *
     * @return the recorded value
     */
    @NotNull
    public String getValue()
    {
        return this.value;
    }

    /**
     * A navigable address for the external target, rendered through the definition's
     * {@link LinkDefinition#getUrlTemplate() URL template}. The recorded value is substituted as-is, so templates
     * are responsible for any encoding their target system needs.
     *
     * @return a URL, or {@code null} if the definition sets no URL template
     */
    @Nullable
    public String getTargetUrl()
    {
        final LinkDefinition definition = this.getDefinition();
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
