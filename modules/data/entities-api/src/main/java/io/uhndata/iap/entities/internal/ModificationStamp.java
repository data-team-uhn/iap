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
package io.uhndata.iap.entities.internal;

import java.time.ZonedDateTime;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.plugins.memory.GenericPropertyState;
import org.apache.jackrabbit.oak.plugins.memory.StringPropertyState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;

import io.uhndata.iap.utils.DateUtils;

/**
 * The {@code mix:lastModified} properties of one commit: when it was made, and by whom. Every node the commit
 * modifies gets the same pair.
 *
 * @param lastModified the {@code jcr:lastModified} property
 * @param lastModifiedBy the {@code jcr:lastModifiedBy} property
 * @version $Id$
 * @since 0.1.0
 */
record ModificationStamp(PropertyState lastModified, PropertyState lastModifiedBy)
{
    /**
     * The stamp of a commit.
     *
     * @param when when the commit was made, the same for every node it stamps
     * @param who the user id the commit carries, as {@code CommitInfo.getUserId()} reports it: {@code oak:unknown}
     *     when no user made it
     * @return the two properties to set
     */
    static ModificationStamp of(final ZonedDateTime when, final String who)
    {
        return new ModificationStamp(
            GenericPropertyState.dateProperty("jcr:lastModified", DateUtils.toString(when)),
            StringPropertyState.stringProperty("jcr:lastModifiedBy", who));
    }

    /**
     * Records this commit as a node's last modification.
     *
     * @param node the node to stamp
     */
    void applyTo(final NodeBuilder node)
    {
        node.setProperty(this.lastModified);
        node.setProperty(this.lastModifiedBy);
    }
}
