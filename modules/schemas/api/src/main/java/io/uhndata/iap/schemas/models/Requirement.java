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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The abstract base for anything a submission must fulfill: a set of questions to answer
 * ({@link FormRequirement}), an expected document ({@link DocumentRequirement}), a required approval
 * ({@link ApprovalRequirement}), or future requirement types. Corresponds to the {@code sch:Requirement} node
 * type, which mixes in {@code sch:Conditionable} (see {@link Conditionable}) so every requirement may carry a
 * single condition controlling whether it applies to a submission. Like {@link FormItem}, this class is
 * deliberately not itself a registered Sling Model (no {@code @Model} annotation): each subtype instead declares
 * {@code adapters = Requirement.class} on its own {@code @Model}, so {@code resource.adaptTo(Requirement.class)}
 * dispatches to the actual concrete subtype (via Sling Models' {@code ResourceTypeBasedResourcePicker}), instead
 * of yielding a generic {@code Requirement} instance lacking a subtype's own fields.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Requirement extends EntityPart implements Conditionable
{
    /** The {@code sling:resourceType} of a {@code sch:Requirement} node. */
    public static final String RESOURCE_TYPE = "sch/Requirement";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

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

    @Override
    public Condition getCondition()
    {
        return this.getChild("sch:condition", Condition.class);
    }
}
