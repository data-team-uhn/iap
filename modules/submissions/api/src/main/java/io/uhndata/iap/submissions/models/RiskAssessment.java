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
import io.uhndata.iap.schemas.models.Risk;

/**
 * A Sling Model wrapping a {@code sub:RiskAssessment} node: a reviewer's assessment of a single schema
 * {@link Risk}, the way an {@link Answer} answers a question.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = RiskAssessment.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class RiskAssessment extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:RiskAssessment} node. */
    public static final String RESOURCE_TYPE = "sub/RiskAssessment";

    @ValueMapValue
    private String risk;

    @ValueMapValue
    private String value;

    /**
     * The risk this assesses.
     *
     * @return a risk, or {@code null} if not set or unresolvable
     */
    @Nullable
    public Risk getRisk()
    {
        return this.getReference(this.risk, Risk.class);
    }

    /**
     * The reviewer's assessment.
     *
     * @return the assessment, or {@code null} if not yet assessed
     */
    @Nullable
    public String getValue()
    {
        return this.value;
    }

    /**
     * Every extraction run against this risk, in the order they ran.
     *
     * @return a list of runs, empty if extraction has never run
     */
    @NotNull
    public List<Extraction> getExtractions()
    {
        return this.getChildren(Extraction.RESOURCE_TYPE, Extraction.class);
    }

    /**
     * The most recent extraction run, which is the one a reviewer is shown.
     *
     * @return the newest run, or {@code null} if extraction has never run
     */
    @Nullable
    public Extraction getLatestExtraction()
    {
        final List<Extraction> extractions = this.getExtractions();
        return extractions.isEmpty() ? null : extractions.get(extractions.size() - 1);
    }
}
