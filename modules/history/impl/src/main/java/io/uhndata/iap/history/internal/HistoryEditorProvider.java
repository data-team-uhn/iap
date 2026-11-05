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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.CompositeEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.commit.EditorProvider;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.entities.nodetypes.NodeTypeRules;
import io.uhndata.iap.utils.DateUtils;

/**
 * Records a commit that a {@link io.uhndata.iap.history.api.HistoryRecorder} described: the action, and what the
 * commit did to each entity, in that same commit. A commit nobody described is left alone.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = EditorProvider.class)
public class HistoryEditorProvider implements EditorProvider
{
    /** Where Oak puts the JCR user data of the session that commits. */
    static final String USER_DATA_KEY = "user-data";

    @Override
    public Editor getRootEditor(final NodeState before, final NodeState after, final NodeBuilder builder,
        final CommitInfo info)
    {
        final Optional<ActionDescription> described =
            ActionDescription.fromUserData(info.getInfo().get(USER_DATA_KEY));
        if (described.isEmpty()) {
            return null;
        }
        final NodeTypeRules rules = new NodeTypeRules(after);
        final EntityLocator entities = new EntityLocator(rules);
        final ChangeLog log = new ChangeLog();
        final RecordWriter writer =
            new RecordWriter(builder, info.getUserId(), DateUtils.toString(ZonedDateTime.now()));
        return CompositeEditor.compose(List.of(new ChangeCollector(after, rules, entities, log),
            new CommitRecord(described.get(), log, entities, writer)));
    }
}
