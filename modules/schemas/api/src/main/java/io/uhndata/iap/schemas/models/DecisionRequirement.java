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
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * The base for a decision a model makes about an uploaded document before anything else is read out of it: a
 * {@link ValidationRequirement} or a {@link ClassificationRequirement}. Corresponds to the abstract
 * {@code sch:DecisionRequirement} node type. Like {@link Requirement}, it is not itself a registered Sling Model.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class DecisionRequirement extends Requirement
{
    /** The confidence an answer needs when the schema names none. */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7;

    /** The child holding the reference text, when there is one. */
    private static final String TEMPLATE_CHILD = "template";

    @ValueMapValue
    private String prompt;

    @ValueMapValue
    private String document;

    @ValueMapValue
    private Double confidenceThreshold;

    /**
     * What the model is asked to decide, and how to tell.
     *
     * @return the prompt, or {@code null} if not set
     */
    @Nullable
    public String getPrompt()
    {
        return this.prompt;
    }

    /**
     * The name of the document requirement whose upload is read.
     *
     * @return the requirement's name, or {@code null} if not set
     */
    @Nullable
    public String getDocumentRequirementName()
    {
        return this.document;
    }

    /**
     * The confidence the model's answer needs to count. A less sure answer counts as unsure.
     *
     * @return the threshold, or {@value #DEFAULT_CONFIDENCE_THRESHOLD} when not set
     */
    public double getConfidenceThreshold()
    {
        return this.confidenceThreshold == null ? DEFAULT_CONFIDENCE_THRESHOLD : this.confidenceThreshold;
    }

    /**
     * Optional reference text the model is shown with the prompt. Read from a {@code template} child, or else a
     * {@code template.md} child, the name a Markdown file keeps when it is loaded as initial content.
     *
     * @return the template file, or {@code null} if there is none
     */
    @Nullable
    public Resource getTemplate()
    {
        final Resource template = this.resource.getChild(TEMPLATE_CHILD);
        return template != null ? template : this.resource.getChild(TEMPLATE_CHILD + ".md");
    }
}
