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
package io.uhndata.iap.workflows.models;

import org.jetbrains.annotations.Nullable;

/**
 * The abstract base shared by the nodes that decide where the workflow goes next rather than doing anything
 * themselves: an {@link ExclusiveGateway}, a {@link ParallelGateway}, or an {@link InclusiveGateway}. Corresponds
 * to the {@code wf:Gateway} node type. Like the other bases here, it is not itself a registered Sling Model.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Gateway extends FlowNode
{
    /** The {@code sling:resourceType} of a {@code wf:Gateway} node. */
    public static final String RESOURCE_TYPE = "wf/Gateway";

    /**
     * The arc to fall back on when no other outgoing arc's condition holds, which is what keeps a conditional
     * gateway from deadlocking. Only meaningful for the gateways that evaluate conditions.
     *
     * @return the default outgoing flow, or {@code null} if none of them is marked as the default
     */
    @Nullable
    public SequenceFlow getDefaultFlow()
    {
        return this.getOutgoingFlows().stream()
            .filter(SequenceFlow::isDefault)
            .findFirst()
            .orElse(null);
    }
}
