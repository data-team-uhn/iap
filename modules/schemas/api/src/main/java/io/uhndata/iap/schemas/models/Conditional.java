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

/**
 * A Sling Model wrapping a {@code sch:Conditional} node: a condition that can be imposed on e.g. the display of a
 * {@link Section} or the requiredness of a {@link Requirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Condition.class, resourceType = Conditional.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Conditional extends Condition
{
    /** The {@code sling:resourceType} of a {@code sch:Conditional} node. */
    public static final String RESOURCE_TYPE = "sch/Conditional";

    @ValueMapValue
    private String comparator;

    @ValueMapValue
    private String dataType;

    /**
     * The comparator applied between {@link #getOperandA()} and {@link #getOperandB()}.
     *
     * @return a comparator name
     */
    public String getComparator()
    {
        return this.comparator;
    }

    /**
     * How the operands should be treated when performing the comparison.
     *
     * @return a data type name, e.g. {@code text}
     */
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * The first, mandatory operand of this condition.
     *
     * @return a conditional value, or {@code null} if not set
     */
    public ConditionalValue getOperandA()
    {
        return this.getChild("operandA", ConditionalValue.class);
    }

    /**
     * The second, optional operand of this condition, needed unless the comparator is singular (e.g. "is empty").
     *
     * @return a conditional value, or {@code null} if not set
     */
    public ConditionalValue getOperandB()
    {
        return this.getChild("operandB", ConditionalValue.class);
    }
}
