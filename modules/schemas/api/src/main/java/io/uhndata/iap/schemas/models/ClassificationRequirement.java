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
 * A Sling Model wrapping a {@code sch:ClassificationRequirement} node: a decision a model makes about an uploaded
 * document before anything is read out of it, such as whether it is a research proposal.
 *
 * <p>It is a form requirement holding one question. The question's options are the categories to choose from, and
 * the model's pick is stored as that question's answer, so the submitter confirms or changes it like any other
 * answer read from a document.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class,
    resourceType = ClassificationRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ClassificationRequirement extends FormRequirement
{
    /** The {@code sling:resourceType} of a {@code sch:ClassificationRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/ClassificationRequirement";

    /** The confidence a pick needs when the schema names none. */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7;

    /** The child holding the reference text, when there is one. */
    private static final String TEMPLATE = "template";

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
     * The name of the document requirement whose upload is classified.
     *
     * @return the requirement's name, or {@code null} if not set
     */
    @Nullable
    public String getDocument()
    {
        return this.document;
    }

    /**
     * Below this confidence the model's pick is not acted on until the submitter confirms it.
     *
     * @return the threshold, between 0 and 1
     */
    public double getConfidenceThreshold()
    {
        return this.confidenceThreshold == null ? DEFAULT_CONFIDENCE_THRESHOLD : this.confidenceThreshold;
    }

    /**
     * Optional reference text the model is shown with the prompt. Read from a {@code template} child, or from
     * {@code template.md}, which is the name a Markdown file keeps when it is loaded as initial content.
     *
     * @return the template file resource, or {@code null} if none was provided
     */
    @Nullable
    public Resource getTemplate()
    {
        final Resource template = this.resource.getChild(TEMPLATE);
        return template != null ? template : this.resource.getChild(TEMPLATE + ".md");
    }

    /**
     * The question the model answers, whose options are the categories.
     *
     * <p>Looked up by walking every child, descending into sections, rather than through
     * {@link #getQuestions()}: a schema may nest this requirement's one question inside a section (for example to
     * give it a heading), and nothing else about a classification requirement needs that question to sit directly
     * under it.</p>
     *
     * @return the first question of this requirement, or {@code null} when it holds none
     */
    @Nullable
    public Question getDecision()
    {
        return firstQuestion(getChildren());
    }

    /**
     * The first {@link Question} found by walking {@code items} depth-first, descending into every
     * {@link Section}.
     *
     * @param items the items to search, in schema order
     * @return the first question found, or {@code null} if none of them holds one
     */
    @Nullable
    private static Question firstQuestion(final List<FormItem> items)
    {
        for (final FormItem item : items) {
            if (item instanceof Question question) {
                return question;
            }
            if (item instanceof Section section) {
                final Question found = firstQuestion(section.getChildren());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The categories the model may pick from, as the decision's options offer them.
     *
     * @return the options, empty when there is no decision question or it offers none
     */
    @NotNull
    public List<OfferedOption> getCategories()
    {
        final Question decision = getDecision();
        return decision == null ? List.of() : decision.getOfferedOptions();
    }
}
