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

/**
 * A Sling Model wrapping a {@code sch:Section} node: a logical grouping of questions, which may be nested.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = QuestionnaireItem.class, resourceType = Section.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Section extends QuestionnaireItem
{
    /** The {@code sling:resourceType} of a {@code sch:Section} node. */
    public static final String RESOURCE_TYPE = "sch/Section";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The human-readable name of this section.
     *
     * @return a title
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * An optional longer description displayed to the submitter.
     *
     * @return a description, or {@code null} if not set
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The subsections nested directly under this section.
     *
     * @return a list of sections, empty if none
     */
    public List<Section> getSections()
    {
        return this.getChildren(Section.RESOURCE_TYPE, Section.class);
    }

    /**
     * The questions grouped directly in this section.
     *
     * @return a list of questions, empty if none
     */
    public List<Question> getQuestions()
    {
        return this.getChildren(Question.RESOURCE_TYPE, Question.class);
    }

    /**
     * The conditions controlling whether this section is shown to the submitter.
     *
     * @return a list of conditionals, empty if none
     */
    public List<Conditional> getConditionals()
    {
        return this.getChildren(Conditional.RESOURCE_TYPE, Conditional.class);
    }

    /**
     * The conditional groups controlling whether this section is shown to the submitter.
     *
     * @return a list of conditional groups, empty if none
     */
    public List<ConditionalGroup> getConditionalGroups()
    {
        return this.getChildren(ConditionalGroup.RESOURCE_TYPE, ConditionalGroup.class);
    }

    /**
     * Every condition controlling whether this section is shown to the submitter, whether a single
     * {@link Conditional} or a {@link ConditionalGroup} (or any future {@link Condition} subtype), each adapted
     * to its own specific model.
     *
     * @return a list of conditions, empty if none
     */
    public List<Condition> getConditions()
    {
        return this.getChildren(Condition.RESOURCE_TYPE, Condition.class);
    }

    /**
     * Every item grouped directly in this section, whether a {@link Question} or a nested {@link Section} (or
     * any future {@link QuestionnaireItem} subtype), each adapted to its own specific model.
     *
     * @return a list of items, empty if none
     */
    public List<QuestionnaireItem> getChildren()
    {
        return this.getChildren(QuestionnaireItem.RESOURCE_TYPE, QuestionnaireItem.class);
    }
}
