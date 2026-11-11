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
package io.uhndata.iap.submissions.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;

/**
 * A Sling Model wrapping a {@code sub:Validation} node: what a model decided about an upload for one
 * {@link ValidationRequirement}, such as whether it is a research proposal. Nobody fills it in, so it holds only the
 * model's runs; {@link #getOutcome()} reads the newest one.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Validation.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Validation extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Validation} node. */
    public static final String RESOURCE_TYPE = "sub/Validation";

    /** The outcome when the model is sure the upload passes. */
    public static final String OUTCOME_YES = "yes";

    /** The outcome when the model is sure the upload does not pass. */
    public static final String OUTCOME_NO = "no";

    /** The outcome when the model found no answer, or one less sure than the requirement asks. */
    public static final String OUTCOME_UNSURE = "unsure";

    @ValueMapValue
    private String requirement;

    /**
     * The requirement this decides.
     *
     * @return a requirement, or {@code null} if not set or unresolvable
     */
    @Nullable
    public ValidationRequirement getRequirement()
    {
        return this.getReference(this.requirement, ValidationRequirement.class);
    }

    /**
     * Every run, in the order they ran.
     *
     * @return a list of runs, empty if the model was never asked
     */
    @NotNull
    public List<Extraction> getExtractions()
    {
        return this.getChildren(Extraction.RESOURCE_TYPE, Extraction.class);
    }

    /**
     * Returns the newest run, the one {@link #getOutcome()} reads.
     *
     * @return the newest run, or {@code null} if the model was never asked
     */
    @Nullable
    public Extraction getLatestExtraction()
    {
        final List<Extraction> extractions = this.getExtractions();
        return extractions.isEmpty() ? null : extractions.get(extractions.size() - 1);
    }

    /**
     * Returns what the newest run decided. A yes or no counts only when the model is as sure as the requirement's
     * threshold; anything else is {@value #OUTCOME_UNSURE}, since the gate must not guess.
     *
     * @return {@value #OUTCOME_YES}, {@value #OUTCOME_NO} or {@value #OUTCOME_UNSURE}, or {@code null} if the model
     *     was never asked
     */
    @Nullable
    public String getOutcome()
    {
        final Extraction latest = this.getLatestExtraction();
        if (latest == null) {
            return null;
        }
        final List<String> values = latest.getExtractedValues();
        final Double confidence = latest.getConfidence();
        if (values.size() != 1 || confidence == null || confidence < this.getConfidenceThreshold()) {
            return OUTCOME_UNSURE;
        }
        final String value = values.get(0);
        return OUTCOME_YES.equals(value) || OUTCOME_NO.equals(value) ? value : OUTCOME_UNSURE;
    }

    /**
     * Whether this decides the given requirement.
     *
     * @param other the requirement to compare with
     * @return {@code true} if this validation's requirement is the same node
     */
    public boolean isAbout(@NotNull final ValidationRequirement other)
    {
        final ValidationRequirement decided = this.getRequirement();
        return decided != null && other.getPath().equals(decided.getPath());
    }

    private double getConfidenceThreshold()
    {
        final ValidationRequirement decided = this.getRequirement();
        return decided == null ? DecisionRequirement.DEFAULT_CONFIDENCE_THRESHOLD : decided.getConfidenceThreshold();
    }
}
