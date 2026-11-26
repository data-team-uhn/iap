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
package io.uhndata.iap.workflows;

import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.utils.NodeTypeUtils;

/**
 * What every element of one parse is read against: the committed repository, its node type registry, and where
 * the parse's problems go.
 *
 * @param root the committed repository root
 * @param registry the root's {@code /jcr:system/jcr:nodeTypes}
 * @param problems where the parse reports what it could not store
 * @version $Id$
 * @since 0.1.0
 */
record ParseScope(NodeState root, NodeState registry, ParseProblems problems)
{
    /**
     * The scope of a parse against this repository root.
     *
     * @param root the committed repository root
     * @param problems where the parse reports what it could not store
     * @return the scope, with the registry looked up once
     */
    static ParseScope of(final NodeState root, final ParseProblems problems)
    {
        return new ParseScope(root, NodeTypeUtils.registry(root), problems);
    }
}
