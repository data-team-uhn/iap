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
 * Resolves operands that read one property, named by the operand value, of some content. The subclass says which
 * content holds the property.
 *
 * <p>A subclass names {@link OperandResolver} as its {@code @Component} service. By default, a component offers only
 * the interfaces its own class implements, not those of a superclass.
 *
 * @version $Id$
 * @since 0.1.0
 */
abstract class AbstractPropertyOperandResolver implements OperandResolver
{
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public String getValueLabel()
    {
        return "Property";
    }

    @Override
    public final Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            this.logger.warn("A {} operand at {} does not name a property", getSource(), operand.getPath());
            return Operand.EMPTY;
        }
        // No declared type: type unification infers it from the stored value
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
