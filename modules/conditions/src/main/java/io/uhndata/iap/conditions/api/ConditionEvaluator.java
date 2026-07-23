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
package io.uhndata.iap.conditions.api;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;

/**
 * Decides whether a condition currently holds, resolving each operand against a context resource — the piece of
 * content the condition is being asked about, e.g. a submission when deciding which of its schema's requirements
 * apply. A condition that cannot be evaluated (unknown comparator, unknown operand source, unknown condition type)
 * is never satisfied, so that content guarded by a broken condition stays hidden rather than leaking.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionEvaluator
{
    /**
     * Evaluate a condition against a context resource.
     *
     * @param condition the condition to evaluate, may be {@code null}
     * @param context the content the condition is evaluated against
     * @return {@code true} if the condition holds, or if there is no condition at all; {@code false} if it doesn't
     *         hold or cannot be evaluated
     */
    boolean isSatisfied(Condition condition, Resource context);

    /**
     * Evaluate the condition guarding a conditionable node against a context resource. Convenience shorthand for
     * {@link #isSatisfied} on {@link Conditionable#getCondition()}.
     *
     * @param conditionable the node whose condition is to be evaluated
     * @param context the content the condition is evaluated against
     * @return {@code true} if the node's condition holds, or if it carries no condition at all; {@code false} if
     *         the condition doesn't hold or cannot be evaluated
     */
    boolean applies(Conditionable conditionable, Resource context);
}
