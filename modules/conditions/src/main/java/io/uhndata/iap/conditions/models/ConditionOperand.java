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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code cond:ConditionOperand} node: the definition of one side of a
 * {@link SingleCondition}'s comparison. The actual values are computed at evaluation time by the
 * {@code OperandResolver} named by {@link #getSource()}, configured by {@link #getValue()}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ConditionOperand.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConditionOperand extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code cond:ConditionOperand} node. */
    public static final String RESOURCE_TYPE = "cond/ConditionOperand";

    /** The source used when none is explicitly set: the stored value itself is the operand value. */
    public static final String DEFAULT_SOURCE = "literal";

    @ValueMapValue
    private String[] value;

    @ValueMapValue
    private String source;

    @ValueMapValue
    private String aggregate;

    /**
     * What the resolver needs to compute the values: the literal values themselves for the {@code literal}
     * source, a question reference for {@code answer}, a property name for {@code property}...
     *
     * @return the stored value(s), or {@code null} if not set
     */
    public String[] getValue()
    {
        return this.value;
    }

    /**
     * The name of the {@code OperandResolver} that computes this operand's actual values at evaluation time.
     *
     * @return a source name, {@value #DEFAULT_SOURCE} if not explicitly set
     */
    public String getSource()
    {
        return this.source == null ? DEFAULT_SOURCE : this.source;
    }

    /**
     * The optional fold collapsing this operand's resolved values into a single value before the comparison, e.g.
     * {@code count} or {@code sum}.
     *
     * @return an aggregator name, or {@code null} if the values are compared as-is
     */
    public String getAggregate()
    {
        return this.aggregate;
    }
}
