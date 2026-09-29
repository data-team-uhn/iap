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

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;

/**
 * Resolves {@code ownProperty} operands: a property of the content the condition is evaluated on itself, named by
 * the operand value, where a {@code property} operand reads the enclosing entity's. A guard about a part of an
 * entity, such as whether a question takes its options from elsewhere, needs the part's own properties.
 *
 * @version $Id$
 * @since 0.1.0
 */
// Named, since the service is implemented through the shared base class, which DS would not look through
@Component(service = OperandResolver.class)
public class OwnPropertyOperandResolver extends AbstractPropertyOperandResolver
{
    @Override
    public String getSource()
    {
        return "ownProperty";
    }

    @Override
    protected Content holder(final Content context)
    {
        return context;
    }
}
