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
package io.uhndata.iap.schemas.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sch:AnswerOption} node, one of the answers a {@link Question} offers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = AnswerOption.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class AnswerOption extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:AnswerOption} node. */
    public static final String RESOURCE_TYPE = "sch/AnswerOption";

    @ValueMapValue
    private String value;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    /**
     * What an answer picking this option stores, and what conditions compare against.
     *
     * @return the stored value
     */
    @NotNull
    public String getValue()
    {
        return this.value;
    }

    /**
     * What the submitter reads, falling back to the {@link #getValue() value}.
     *
     * @return a label, never empty
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.value : this.label;
    }

    /**
     * When this option applies, for a person choosing and for a model matching.
     *
     * @return a description, or {@code null} when there is nothing more to say than the label
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }
}
