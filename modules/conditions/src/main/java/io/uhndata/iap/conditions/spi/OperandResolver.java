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
package io.uhndata.iap.conditions.spi;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.entities.models.Entity;

/**
 * Computes the actual values of one side of a comparison at evaluation time. Each resolver implements one operand
 * source (e.g. {@code literal}, {@code answer}, {@code tags}, {@code property}) and is picked by matching its
 * {@link #getSource() name} against the {@code source} property of the {@code cond:ConditionOperand} being
 * resolved; registering a new resolver service is all it takes to support a new kind of operand.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface OperandResolver
{
    /**
     * The operand source this resolver implements, matched against the {@code source} property of operand nodes.
     *
     * @return a source name, e.g. {@code answer}
     */
    String getSource();

    /**
     * Compute the actual values of an operand, in their natural stored types — coercion for comparison is the
     * evaluator's job, after it unifies the types of the two sides. A resolver that knows the authoritative type
     * of its values (e.g. the referenced question's declared data type) states it on the returned operand via
     * {@link Operand#of(Object, OperandType)}, steering that unification.
     *
     * @param operand the operand definition being resolved
     * @param context the content the condition is evaluated against
     * @return the resolved values, never {@code null}; an empty operand when nothing matches the definition, so
     *         that the {@code is empty} operator can test for absence
     */
    Operand resolve(ConditionOperand operand, Resource context);

    /**
     * The entity enclosing a resource, e.g. the submission an answer belongs to. This is the record a condition is
     * usually asked about, so resolvers commonly interpret their operand definition relative to it rather than to
     * the exact context resource, which may be anywhere inside the entity.
     *
     * @param resource a resource, may be {@code null}
     * @return the closest ancestor-or-self that is an {@code iap:Entity}, or {@code null} if there is none
     */
    static Resource findEnclosingEntity(final Resource resource)
    {
        Resource current = resource;
        while (current != null && !current.isResourceType(Entity.RESOURCE_TYPE)) {
            current = current.getParent();
        }
        return current;
    }
}
