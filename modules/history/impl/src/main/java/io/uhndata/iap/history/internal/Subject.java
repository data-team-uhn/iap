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
package io.uhndata.iap.history.internal;

import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * An entity a commit affected, as an entry names it.
 *
 * @param uuid its {@code jcr:uuid}
 * @param path its absolute path
 * @param type its primary node type
 * @version $Id$
 * @since 0.1.0
 */
record Subject(String uuid, String path, String type)
{
    /**
     * The subject a node stands for.
     *
     * @param path the node's absolute path
     * @param node the node, as it was before the commit if the commit removes it
     * @return the subject, or {@code null} if the node has no {@code jcr:uuid} to name an entry after
     */
    static Subject of(final String path, final NodeState node)
    {
        final String uuid = node.getString("jcr:uuid");
        return uuid == null ? null : new Subject(uuid, path, node.getName("jcr:primaryType"));
    }
}
