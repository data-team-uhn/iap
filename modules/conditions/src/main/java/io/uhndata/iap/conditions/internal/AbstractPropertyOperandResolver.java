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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;

/**
 * What resolves operands reading one property, named by the operand value, of some content the condition is about:
 * the subclass says which.
 *
 * @version $Id$
 * @since 0.1.0
 */
abstract class AbstractPropertyOperandResolver implements OperandResolver
{
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public final Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            this.logger.warn("A {} operand at {} does not name a property", getSource(), operand.getPath());
            return Operand.EMPTY;
        }
        // Raw, undeclared: the stored JCR type of the property speaks for itself in the type unification
        return Operand.of(holder(context).get(value[0]));
    }

    /**
     * The content whose property is read.
     *
     * @param context the content the condition is evaluated on
     * @return the content holding the property
     */
    protected abstract Content holder(Content context);
}
