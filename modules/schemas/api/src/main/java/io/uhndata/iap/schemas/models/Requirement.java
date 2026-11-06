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

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.utils.Referenceable;

/**
 * The base of the models of what a submission must fulfill. A requirement is a {@link FormRequirement form} to
 * answer, a {@link DocumentRequirement document} to provide, an {@link ApprovalRequirement approval} to obtain, or a
 * {@link SectionRequirement section} a document must contain. Each may carry one {@link Condition} deciding whether
 * it applies to a submission.
 *
 * <p>This class is not a Sling Model itself. Each subtype lists {@code Requirement} among its {@code adapters}, so
 * {@code resource.adaptTo(Requirement.class)} returns the model of the resource's own type, with its own fields.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Requirement extends EntityPart implements Conditionable, Referenceable
{
    /** The {@code sling:resourceType} of a {@code sch:Requirement} node. */
    public static final String RESOURCE_TYPE = "sch/Requirement";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    /**
     * The requirement's short name, such as "Patient consent".
     *
     * @return the label
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * What the submitter reads about what is expected.
     *
     * @return the description; {@code null} if there is none
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    @Override
    @Nullable
    public Condition getCondition()
    {
        return this.getChild("cond:condition", Condition.class);
    }

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }
}
