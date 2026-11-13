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
 * A Sling Model wrapping a {@code sch:ClassificationRequirement} node: which kind of request an uploaded document
 * is, such as which kind of study.
 *
 * <p>The categories are the options of an ordinary question in a form requirement, which this requirement
 * references. The model's pick is saved as a suggestion on that question's answer, and the submitter confirms
 * it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class,
    resourceType = ClassificationRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ClassificationRequirement extends DecisionRequirement
{
    /** The {@code sling:resourceType} of a {@code sch:ClassificationRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/ClassificationRequirement";

    @ValueMapValue
    private String question;

    /**
     * The question whose options are the categories.
     *
     * @return the question, or {@code null} when the reference is not set or does not resolve
     */
    @Nullable
    public Question getQuestion()
    {
        return this.getReference(this.question, Question.class);
    }

    /**
     * The categories the model may pick from: the options of the referenced question.
     *
     * @return the options, empty when there is no question or it has no options
     */
    @NotNull
    public List<OfferedOption> getCategories()
    {
        final Question categories = getQuestion();
        return categories == null ? List.of() : categories.getOfferedOptions();
    }
}
