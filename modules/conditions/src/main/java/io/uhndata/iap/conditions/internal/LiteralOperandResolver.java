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
package io.uhndata.iap.conditions.internal;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * Resolves {@code literal} operands, the default source: the values stored in the operand definition itself are
 * the operand values.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class LiteralOperandResolver implements OperandResolver
{
    @Override
    public String getSource()
    {
        return ConditionOperand.DEFAULT_SOURCE;
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Resource context)
    {
        // Read the raw property rather than the model's String[] view, preserving the stored JCR
        // types (LONG, BOOLEAN, DATE...) for the evaluator's type unification.
        return Operand.of(operand.get("value"));
    }
}
