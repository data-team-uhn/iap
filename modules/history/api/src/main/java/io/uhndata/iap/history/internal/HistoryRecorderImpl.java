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

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
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
    /** Where the record lives. */
    static final String ROOT = "/History";

    /** The type of the store root and of the prefix-tree buckets under it. */
    static final String BUCKET_TYPE = "hist:Log";

    /** The subservice whose user maintains the buckets. */
    static final String SUBSERVICE = "history";

    private static final String ACTION_TYPE = "hist:Action";

    private static final String ENTRY_TYPE = "hist:Entry";

    private static final String COMPLETE = "complete";

    private static final String SNAPSHOT = "snapshot";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    @NotNull
    public String record(@NotNull final Session session, @NotNull final RecordedAction action)
        throws RepositoryException
    {
        final String name = this.newActionName();
        final String path = PrefixTree.pathFor(ROOT, name);
        final Node bucket = this.bucketFor(session, path, name);

        final Node node = bucket.addNode(name, ACTION_TYPE);
        node.setProperty("actor", action.getActor());
        node.setProperty("operation", action.getOperation());
        setIfPresent(node, "onBehalfOf", action.getOnBehalfOf());
        setIfPresent(node, "workflowInstance", action.getWorkflowInstance());
        setIfPresent(node, "workflowVersion", action.getWorkflowVersion());
        setIfPresent(node, "activityId", action.getActivityId());
        setIfPresent(node, "activityLabel", action.getActivityLabel());
        setIfPresent(node, "taskInstance", action.getTaskInstance());
        setIfPresent(node, "outcome", action.getOutcome());
        setIfPresent(node, "outcomeNote", action.getOutcomeNote());
        setIfPresent(node, "event", action.getEvent());
        setIfPresent(node, "component", action.getComponent());
        setIfPresent(node, "parentAction", action.getParentAction());

        for (final RecordedEffect effect : action.getEffects()) {
            // Named after the affected resource, so that "what did this action do to that resource" is a path rather
            // than a search, and so that the repository itself refuses a second entry for the same one
            final Node entry = node.addNode(effect.subject(), ENTRY_TYPE);
            entry.setProperty("subject", effect.subject());
            entry.setProperty("subjectPath", effect.subjectPath());
            entry.setProperty("subjectType", effect.subjectType());
            entry.setProperty("role", effect.role());
            if (!effect.changes().isEmpty()) {
                entry.setProperty("changes", effect.changes().toArray(new String[0]));
            }
        }
        return node.getPath();
    }

    @Override
    public void completeSnapshots(@NotNull final Session session, @NotNull final String actionPath,
        @NotNull final Map<String, String> snapshots) throws RepositoryException
    {
        final Node action = session.getNode(actionPath);
        for (final Map.Entry<String, String> taken : snapshots.entrySet()) {
            if (!action.hasNode(taken.getKey())) {
                throw new PathNotFoundException(
                    "The action at " + actionPath + " did not affect " + taken.getKey());
            }
            action.getNode(taken.getKey()).setProperty(SNAPSHOT, taken.getValue());
        }
        action.setProperty(COMPLETE, true);
        session.save();
    }

    /**
     * The name a new action is filed under: random, because the prefix tree spreads nodes by the leading characters of
     * their own names, and only a uniformly distributed name spreads.
     *
     * <p>
     * Overridable so that a test can pin it. Which of this class's two paths runs depends on whether the bucket for a
     * given name already exists, and with the name random there is no other way to ask for either of them.
     * </p>
     *
     * @return a fresh name, uniformly distributed
     */
    String newActionName()
    {
        return UUID.randomUUID().toString();
    }

    /**
     * The bucket an action goes in, created if this is the first action to land in it.
     *
     * <p>
     * Creating one cannot happen in the caller's session: {@link PrefixTree#bucketFor} saves each bucket as it makes
     * it, and calls {@code refresh(false)} to recover from a race — which would commit half of the caller's work, or
     * discard it. So a bucket that does not exist yet is made in a session of the store's own, and the caller's
     * session is then refreshed to see it, keeping its pending changes. Buckets are inert and shared, so making one
     * outside the caller's transaction costs nothing even if that transaction then fails.
     * </p>
     */
    private Node bucketFor(final Session session, final String actionPath, final String name)
        throws RepositoryException
    {
        final String bucketPath = actionPath.substring(0, actionPath.lastIndexOf('/'));
        if (session.nodeExists(bucketPath)) {
            return session.getNode(bucketPath);
        }
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Session own = resolver.adaptTo(Session.class);
            if (own == null) {
                throw new RepositoryException("The history store has no session to create its buckets in");
            }
            PrefixTree.bucketFor(own.getNode(ROOT), name, BUCKET_TYPE);
        } catch (final LoginException e) {
            throw new RepositoryException("The history store cannot reach its own service user", e);
        }
        // Keeping the caller's own pending changes, which is the whole reason this is not a plain refresh
        session.refresh(true);
        return session.getNode(bucketPath);
    }

    private static void setIfPresent(final Node node, final String property, final String value)
        throws RepositoryException
    {
        if (value != null) {
            node.setProperty(property, value);
        }
    }
}
