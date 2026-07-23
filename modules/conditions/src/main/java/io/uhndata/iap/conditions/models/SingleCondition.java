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
package io.uhndata.iap.conditions.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * A Sling Model wrapping a {@code cond:SingleCondition} node: a single comparison between two operands, imposed on
 * the node carrying it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Condition.class, resourceType = SingleCondition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SingleCondition extends Condition
{
    /** The {@code sling:resourceType} of a {@code cond:SingleCondition} node. */
    public static final String RESOURCE_TYPE = "cond/SingleCondition";

    @ValueMapValue
    private String comparator;

    /**
     * The comparator applied between {@link #getOperandA()} and {@link #getOperandB()}.
     *
     * @return a comparator name, e.g. {@code equals}
     */
    public String getComparator()
    {
        return this.comparator;
    }

    /**
     * The first, mandatory operand of this condition.
     *
     * @return a condition operand, or {@code null} if not set
     */
    public ConditionOperand getOperandA()
    {
        return this.getChild("operandA", ConditionOperand.class);
    }

    /**
     * The second, optional operand of this condition, needed unless the comparator is unary (e.g. "is empty").
     *
     * @return a condition operand, or {@code null} if not set
     */
    public ConditionOperand getOperandB()
    {
        return this.getChild("operandB", ConditionOperand.class);
    }
}
