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
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sch:ConditionalValue} node: either a literal value, or a reference to the
 * question whose answer supplies the value, used as an operand of a {@link Conditional}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ConditionalValue.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConditionalValue extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:ConditionalValue} node. */
    public static final String RESOURCE_TYPE = "sch/ConditionalValue";

    @ValueMapValue
    private String[] value;

    @ValueMapValue(name = "isReference")
    private boolean reference;

    /**
     * The value, or a reference to the question whose answer provides the value.
     *
     * @return the stored value(s), or {@code null} if not set
     */
    public String[] getValue()
    {
        return this.value;
    }

    /**
     * Whether {@link #getValue()} is a reference to a question rather than a literal.
     *
     * @return {@code true} if the value is a question reference
     */
    public boolean isReference()
    {
        return this.reference;
    }
}
