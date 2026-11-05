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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Records a described commit once it has been walked. It sits beside the {@link ChangeCollector} at the root, and
 * leaves after it, when the log holds the whole commit.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CommitRecord extends DefaultEditor
{
    private final ActionDescription description;

    private final ChangeLog log;

    private final RecordWriter writer;

    /**
     * Constructor.
     *
     * @param description the action the commit was described as
     * @param log what the commit did to each entity, filled in by the walk
     * @param writer writes the record into the commit
     */
    CommitRecord(final ActionDescription description, final ChangeLog log, final RecordWriter writer)
    {
        this.description = description;
        this.log = log;
        this.writer = writer;
    }

    @Override
    public void leave(final NodeState before, final NodeState after) throws CommitFailedException
    {
        this.writer.write(this.description, this.log);
    }
}
