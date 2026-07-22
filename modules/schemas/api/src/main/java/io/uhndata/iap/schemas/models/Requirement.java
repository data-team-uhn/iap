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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sch:Requirement} node, the abstract base for anything a submission must fulfill:
 * a set of questions to answer ({@link QuestionnaireRequirement}), an expected document
 * ({@link DocumentRequirement}), a required approval ({@link ApprovalRequirement}), or future requirement types.
 * Adapting a concrete requirement resource to this class, rather than to its own specific subtype, yields only
 * these common properties, same as adapting an entity to {@link io.uhndata.iap.entities.models.Entity} instead of
 * to its own specific subtype.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Requirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Requirement extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:Requirement} node. */
    public static final String RESOURCE_TYPE = "sch/Requirement";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private boolean required;

    /**
     * The short name of this requirement, e.g. "Patient consent".
     *
     * @return a label
     */
    public String getLabel()
    {
        return this.label;
    }

    /**
     * A description displayed to the submitter, explaining what is expected.
     *
     * @return a description, or {@code null} if not set
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether this requirement must be fulfilled before the submission can be approved, once it applies (see
     * {@link #getConditions()} for whether it applies at all).
     *
     * @return {@code true} if this requirement is mandatory
     */
    public boolean isRequired()
    {
        return this.required;
    }

    /**
     * Every condition controlling whether this requirement applies to a submission, each adapted to its own
     * specific model regardless of whether it is a {@link SingleCondition}, a {@link ConditionGroup}, or any
     * future {@link Condition} subtype.
     *
     * @return a list of conditions, empty if none
     */
    public List<Condition> getConditions()
    {
        return this.getChildren(Condition.RESOURCE_TYPE, Condition.class);
    }
}
