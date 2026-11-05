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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.Objects;

import javax.jcr.Node;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The nodes behind the resources the content tasks act on, which are always stored in the repository, and the
 * resources in front of the nodes they write.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Nodes
{
    private Nodes()
    {
        // Utility class
    }

    /**
     * The node behind a resource.
     *
     * @param resource a resource stored in the repository
     * @return its node
     */
    static Node of(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(Node.class), "Content is stored in a JCR repository");
    }

    /**
     * The resource in front of a node a content task has just written.
     *
     * @param context the executing task's context
     * @param path where the node now is
     * @return its resource
     */
    static Resource written(final WorkflowTaskContext context, final String path)
    {
        return Objects.requireNonNull(context.getResourceResolver().getResource(path),
            "What a task has just written can be read in the session that wrote it");
    }
}
