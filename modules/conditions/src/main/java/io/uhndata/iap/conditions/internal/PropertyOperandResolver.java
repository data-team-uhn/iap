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

import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * Resolves {@code property} operands: an arbitrary metadata property of the enclosing entity, named by the operand
 * value — e.g. a submission's workflow-managed {@code status}, or repository-managed audit properties like
 * {@code jcr:createdBy}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class PropertyOperandResolver implements OperandResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyOperandResolver.class);

    @Override
    public String getSource()
    {
        return "property";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Resource context)
    {
        if (operand.getValue() == null || operand.getValue().length == 0) {
            LOGGER.warn("Property operand at {} does not name a property", operand.getPath());
            return Operand.EMPTY;
        }
        final Resource entity = Optional.ofNullable(OperandResolver.findEnclosingEntity(context)).orElse(context);
        // Raw, undeclared: the stored JCR type of the property speaks for itself in the type unification
        return Operand.of(entity.getValueMap().get(operand.getValue()[0]));
    }
}
