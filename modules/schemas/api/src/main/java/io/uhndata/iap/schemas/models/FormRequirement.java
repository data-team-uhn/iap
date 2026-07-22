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

/**
 * A Sling Model wrapping a {@code sch:FormRequirement} node: a set of questions, grouped into sections, that the
 * submitter must answer to fulfill this requirement. This is how a schema version's own questions are expressed:
 * as a (typically unconditional, always required) form requirement.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = FormRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class FormRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:FormRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/FormRequirement";

    /**
     * The sections making up this form.
     *
     * @return a list of sections, empty if none
     */
    public List<Section> getSections()
    {
        return this.getChildren(Section.RESOURCE_TYPE, Section.class);
    }

    /**
     * The questions directly making up this form, outside of any section.
     *
     * @return a list of questions, empty if none
     */
    public List<Question> getQuestions()
    {
        return this.getChildren(Question.RESOURCE_TYPE, Question.class);
    }

    /**
     * Every question or section directly making up this form (or any future {@link FormItem} subtype), in the
     * order they appear, each adapted to its own specific model rather than to a common, less specific one.
     *
     * @return a list of items, empty if none
     */
    public List<FormItem> getChildren()
    {
        return this.getChildren(FormItem.RESOURCE_TYPE, FormItem.class);
    }
}
