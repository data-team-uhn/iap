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

import java.util.Calendar;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.history.models.Action;
import io.uhndata.iap.history.models.Entry;
import io.uhndata.iap.utils.PrefixTree;

/**
 * Writes actions and their effects under {@code /History}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HistoryRecorder.class)
public class HistoryRecorderImpl implements HistoryRecorder
{
    /** The path of the node holding the whole history. */
    static final String ROOT_PATH = "/History";

    /** The type of the store root and of the prefix-tree buckets under it. */
    static final String BUCKET_TYPE = "hist:Log";

    /** The subservice whose user maintains the buckets. */
    static final String SUBSERVICE_NAME = "history";

    private static final String ACTION_TYPE = "hist:Action";

    private static final String ENTRY_TYPE = "hist:Entry";

    /** The optional properties of a {@code hist:Action}, each with the getter it is read from; written only if set. */
    private static final Map<String, Function<RecordedAction, String>> OPTIONAL_CAUSE_PROPERTIES = Map.ofEntries(
        Map.entry(Action.ON_BEHALF_OF_PROPERTY, RecordedAction::getOnBehalfOf),
        Map.entry(Action.WORKFLOW_INSTANCE_PROPERTY, RecordedAction::getWorkflowInstance),
        Map.entry(Action.WORKFLOW_VERSION_PROPERTY, RecordedAction::getWorkflowVersion),
        Map.entry(Action.ACTIVITY_ID_PROPERTY, RecordedAction::getActivityId),
        Map.entry(Action.ACTIVITY_LABEL_PROPERTY, RecordedAction::getActivityLabel),
        Map.entry(Action.TASK_INSTANCE_PROPERTY, RecordedAction::getTaskInstance),
        Map.entry(Action.OUTCOME_PROPERTY, RecordedAction::getOutcome),
        Map.entry(Action.OUTCOME_NOTE_PROPERTY, RecordedAction::getOutcomeNote),
        Map.entry(Action.EVENT_PROPERTY, RecordedAction::getEvent),
        Map.entry(Action.COMPONENT_PROPERTY, RecordedAction::getComponent),
        Map.entry(Action.PARENT_ACTION_PROPERTY, RecordedAction::getParentAction));

    private final ResourceResolverFactory resolverFactory;

    private final Supplier<String> actionNames;

    /**
     * Files each action under a random name: the prefix tree spreads nodes by the leading characters of their own
     * names, and only a uniformly distributed name spreads.
     *
     * @param resolverFactory gives the store a session of its own, to create buckets in
     */
    @Activate
    public HistoryRecorderImpl(@Reference final ResourceResolverFactory resolverFactory)
    {
        this(resolverFactory, () -> UUID.randomUUID().toString());
    }

    HistoryRecorderImpl(final ResourceResolverFactory resolverFactory, final Supplier<String> actionNames)
    {
        this.resolverFactory = resolverFactory;
        this.actionNames = actionNames;
    }

    @Override
    @NotNull
    public String record(@NotNull final Session session, @NotNull final RecordedAction action)
        throws RepositoryException
    {
        final String name = this.actionNames.get();
        final Node actionNode = this.bucketFor(session, name).addNode(name, ACTION_TYPE);
        writeCause(actionNode, action);
        for (final RecordedEffect effect : action.getEffects()) {
            writeEntry(actionNode, effect);
        }
        return actionNode.getPath();
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

    /**
     * The bucket an action goes in, created if this is the first action to land in it.
     *
     * <p>
     * A missing bucket is created and saved in a session of the store's own, and the caller's session is then
     * refreshed to see it. In the caller's session, {@link PrefixTree#bucketFor} would save or discard the caller's
     * pending work. {@link PrefixTree#pendingBucketFor} would make two callers adding the same new bucket conflict, and
     * fail the second caller's change. A bucket left behind by a transaction that fails is empty and harmless.
     * </p>
     */
    private Node bucketFor(final Session session, final String name) throws RepositoryException
    {
        final String bucketPath = PrefixTree.bucketPathFor(ROOT_PATH, name);
        if (!session.nodeExists(bucketPath)) {
            this.createBucketAsTheStore(name);
            // true keeps the caller's pending changes
            session.refresh(true);
        }
        return session.getNode(bucketPath);
    }

    private void createBucketAsTheStore(final String name) throws RepositoryException
    {
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Session own = resolver.adaptTo(Session.class);
            if (own == null) {
                throw new RepositoryException("The history store has no session to create its buckets in");
            }
            PrefixTree.bucketFor(own.getNode(ROOT_PATH), name, BUCKET_TYPE);
        } catch (final LoginException e) {
            throw new RepositoryException("The history store cannot reach its own service user", e);
        }
    }

    private static void writeCause(final Node actionNode, final RecordedAction action) throws RepositoryException
    {
        actionNode.setProperty(Action.CREATED_BY_PROPERTY, action.getRequestedBy());
        actionNode.setProperty(Action.OPERATION_PROPERTY, action.getOperation());
        for (final Map.Entry<String, Function<RecordedAction, String>> optional
            : OPTIONAL_CAUSE_PROPERTIES.entrySet()) {
            final String value = optional.getValue().apply(action);
            if (value != null) {
                actionNode.setProperty(optional.getKey(), value);
            }
        }
        final Calendar occurredAt = action.getOccurredAt();
        if (occurredAt != null) {
            actionNode.setProperty(Action.OCCURRED_AT_PROPERTY, occurredAt);
        }
    }

    /**
     * One entry, named after the affected resource. "What did this action do to that resource" is then a path rather
     * than a search, and the repository refuses a second entry for the same one.
     */
    private static void writeEntry(final Node actionNode, final RecordedEffect effect) throws RepositoryException
    {
        final Node entry = actionNode.addNode(effect.subject(), ENTRY_TYPE);
        entry.setProperty(Entry.SUBJECT_PROPERTY, effect.subject());
        entry.setProperty(Entry.SUBJECT_PATH_PROPERTY, effect.subjectPath());
        entry.setProperty(Entry.SUBJECT_TYPE_PROPERTY, effect.subjectType());
        entry.setProperty(Entry.ROLE_PROPERTY, effect.role());
        if (!effect.changes().isEmpty()) {
            entry.setProperty(Entry.CHANGES_PROPERTY, effect.changes().toArray(new String[0]));
        }
    }
}
