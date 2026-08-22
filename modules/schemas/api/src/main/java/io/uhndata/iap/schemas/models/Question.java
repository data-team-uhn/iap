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
 * A Sling Model wrapping a {@code sch:Question} node: a single question the submitter must answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FormItem.class, resourceType = Question.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Question extends FormItem
{
    /** The {@code sling:resourceType} of a {@code sch:Question} node. */
    public static final String RESOURCE_TYPE = "sch/Question";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private boolean required;

    @ValueMapValue
    private boolean multiple;

    @ValueMapValue
    private String displayMode;

    @ValueMapValue
    private String purpose;

    @ValueMapValue
    private String extractionPrompt;

    @ValueMapValue
    private String responseShape;

    @ValueMapValue
    private String[] rubricTags;

    /**
     * The question text shown to the submitter.
     *
     * @return the question text
     */
    @NotNull
    public String getText()
    {
        return this.text;
    }

    /**
     * An optional longer explanation displayed to the submitter.
     *
     * @return a description, or {@code null} if not set
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The expected answer type.
     *
     * @return a data type name, e.g. {@code text}, {@code long}, {@code boolean}, {@code file}
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * Whether an answer must be provided before submitting.
     *
     * @return {@code true} if an answer is required
     */
    public boolean isRequired()
    {
        return this.required;
    }

    /**
     * Whether more than one value may be provided.
     *
     * @return {@code true} if multiple values are allowed
     */
    public boolean isMultiple()
    {
        return this.multiple;
    }

    /**
     * How the question is rendered to the submitter.
     *
     * @return a display mode, or {@code null} to let the answer's data type decide
     */
    @Nullable
    public String getDisplayMode()
    {
        return this.displayMode;
    }

    /**
     * What the answer is used to judge, in the reviewer's terms rather than the submitter's.
     *
     * @return a purpose, or {@code null} if not stated
     */
    @Nullable
    public String getPurpose()
    {
        return this.purpose;
    }

    /**
     * The prompt an LLM is given to read this answer out of the submitted documents.
     *
     * @return a prompt, or {@code null} if this answer is not extracted
     */
    @Nullable
    public String getExtractionPrompt()
    {
        return this.extractionPrompt;
    }

    /**
     * The shape the LLM's reply must take, as a JSON schema.
     *
     * @return a JSON schema, or {@code null} if the reply is unconstrained
     */
    @Nullable
    public String getResponseShape()
    {
        return this.responseShape;
    }

    /**
     * The tags of the schema sections that may contain the answer. Chunk selection for extraction is tag-driven:
     * the union of the extracted questions' tags decides which chunks a model is given.
     *
     * @return a list of tags, empty if none were assigned
     */
    @NotNull
    public List<String> getRubricTags()
    {
        return this.rubricTags == null ? List.of() : List.of(this.rubricTags);
    }
}
