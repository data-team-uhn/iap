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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code sch:Section} node: a group of questions in a form. Sections can nest.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FormItem.class, resourceType = Section.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Section extends FormItem
{
    /** The {@code sling:resourceType} of a {@code sch:Section} node. */
    public static final String RESOURCE_TYPE = "sch/Section";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The section's heading, as the submitter reads it.
     *
     * @return the title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * What the submitter reads under the heading.
     *
     * @return the description; {@code null} if there is none
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The sections directly in this section.
     *
     * @return the sections, in stored order; empty if there are none
     */
    @NotNull
    public List<Section> getSections()
    {
        return this.getChildren(Section.RESOURCE_TYPE, Section.class);
    }

    /**
     * The questions directly in this section.
     *
     * @return the questions, in stored order; empty if there are none
     */
    @NotNull
    public List<Question> getQuestions()
    {
        return this.getChildren(Question.RESOURCE_TYPE, Question.class);
    }

    /**
     * Every item directly in this section, each read as the model of its own type, such as {@link Question}.
     *
     * @return the items, in stored order; empty if there are none
     */
    @NotNull
    public List<FormItem> getChildren()
    {
        return this.getChildren(FormItem.RESOURCE_TYPE, FormItem.class);
    }
}
