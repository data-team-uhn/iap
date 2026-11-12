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
 * A Sling Model wrapping a {@code sch:FormRequirement} node: a form, made of questions grouped into sections, that the
 * submitter answers. A schema version asks its questions through form requirements.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class, resourceType = FormRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class FormRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:FormRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/FormRequirement";

    @ValueMapValue
    private String[] documents;

    /**
     * The names of the document requirements whose uploads a model reads to answer this form's questions, in the
     * order they are sent. A form that names none is not read: its questions are left to the submitter.
     *
     * @return the names, empty when the form names none
     */
    @NotNull
    public List<String> getDocumentRequirementNames()
    {
        return this.documents == null ? List.of() : List.of(this.documents);
    }

    /**
     * Optional reference text the model is shown with this form's questions. Read from a {@code template} child,
     * or else a {@code template.md} child.
     *
     * @return the template file, or {@code null} if there is none
     */
    @Nullable
    public Resource getTemplate()
    {
        return getReferenceTemplate();
    }

    /**
     * The sections directly in this form.
     *
     * @return the sections, in stored order; empty if there are none
     */
    @NotNull
    public List<Section> getSections()
    {
        return this.getChildren(Section.RESOURCE_TYPE, Section.class);
    }

    /**
     * The questions directly in this form, outside any section.
     *
     * @return the questions, in stored order; empty if there are none
     */
    @NotNull
    public List<Question> getQuestions()
    {
        return this.getChildren(Question.RESOURCE_TYPE, Question.class);
    }

    /**
     * Every item directly in this form, each read as the model of its own type, such as {@link Question}.
     *
     * @return the items, in stored order; empty if there are none
     */
    @NotNull
    public List<FormItem> getChildren()
    {
        return this.getChildren(FormItem.RESOURCE_TYPE, FormItem.class);
    }
}
