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

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.ObservationManager;

import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.models.Action;
import io.uhndata.iap.history.models.Entry;
import io.uhndata.iap.utils.PrefixTree;

/**
 * Saves a change with its {@link ActionDescription description}, which {@link HistoryEditorProvider} reads from the
 * commit.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HistoryRecorder.class)
public class HistoryRecorderImpl implements HistoryRecorder
{
    private final Supplier<String> actionNames;

    /**
     * Constructor naming each action after a random UUID.
     */
    public HistoryRecorderImpl()
    {
        this(() -> UUID.randomUUID().toString());
    }

    /**
     * Constructor.
     *
     * @param actionNames hands out the name of each new action
     */
    HistoryRecorderImpl(final Supplier<String> actionNames)
    {
        this.actionNames = actionNames;
    }

    @Override
    @NotNull
    public String commit(@NotNull final Session session, @NotNull final RecordedAction action)
        throws RepositoryException
    {
        final ActionDescription description = ActionDescription.of(this.actionNames.get(), action);
        final ObservationManager observation = session.getWorkspace().getObservationManager();
        observation.setUserData(description.toUserData());
        try {
            session.save();
        } finally {
            // The session would otherwise hand the description to each of its later commits
            observation.setUserData(null);
        }
        return PrefixTree.pathFor(RecordWriter.ROOT_PATH, description.name());
    }

    @Override
    public void completeSnapshots(@NotNull final Session session, @NotNull final String actionPath,
        @NotNull final Map<String, String> snapshots) throws RepositoryException
    {
        final Node actionNode = session.getNode(actionPath);
        for (final Map.Entry<String, String> taken : snapshots.entrySet()) {
            if (!actionNode.hasNode(taken.getKey())) {
                throw new PathNotFoundException(
                    "The action at " + actionPath + " did not affect " + taken.getKey());
            }
            actionNode.getNode(taken.getKey()).setProperty(Entry.SNAPSHOT_PROPERTY, taken.getValue());
        }
        actionNode.setProperty(Action.COMPLETE_PROPERTY, true);
        session.save();
    }
}
