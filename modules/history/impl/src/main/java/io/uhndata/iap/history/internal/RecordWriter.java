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

import java.util.List;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.history.models.Action;
import io.uhndata.iap.history.models.Entry;
import io.uhndata.iap.history.models.Log;
import io.uhndata.iap.utils.PrefixTree;

/**
 * Writes an action and its entries into the commit that did it.
 *
 * <p>A commit editor writes below the repository's node type checks. So the nodes are written whole here: their
 * type, and every property their type would otherwise fill in or insist on.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RecordWriter
{
    /** Where the history is stored. */
    static final String ROOT_PATH = "/History";

    /** The code a commit is refused with when its action is recorded already. */
    private static final int ALREADY_RECORDED_CODE = 1;

    private static final String ACTION_TYPE = "hist:Action";

    private static final String ENTRY_TYPE = "hist:Entry";

    private static final String LOG_TYPE = "hist:Log";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    private static final String RESOURCE_SUPER_TYPE_PROPERTY = "sling:resourceSuperType";

    private static final String CREATED_PROPERTY = "jcr:created";

    private static final String CREATED_BY_PROPERTY = "jcr:createdBy";

    private final NodeBuilder root;

    /** Who committed, for the {@code jcr:createdBy} of what is written. */
    private final String writer;

    /** When, for the {@code jcr:created} of what is written. */
    private final String now;

    /**
     * Constructor.
     *
     * @param root the root builder of the commit
     * @param writer the user whose session commits
     * @param now when it commits, formatted as a repository date
     */
    RecordWriter(final NodeBuilder root, final String writer, final String now)
    {
        this.root = root;
        this.writer = writer;
        this.now = now;
    }

    /**
     * Writes the action, with one entry for each entity it affected.
     *
     * @param description the action, and the name to file it under
     * @param log what the commit did to each entity
     * @throws CommitFailedException if an action of that name is already recorded; a constraint violation, so that
     *             nobody takes it for a concurrent change and tries again
     */
    void write(final ActionDescription description, final ChangeLog log) throws CommitFailedException
    {
        final NodeBuilder bucket = bucketFor(description.name());
        if (bucket.hasChildNode(description.name())) {
            throw new CommitFailedException(CommitFailedException.CONSTRAINT, ALREADY_RECORDED_CODE,
                "An action named " + description.name() + " is already recorded");
        }
        final NodeBuilder action = created(bucket.child(description.name()), ACTION_TYPE, Action.RESOURCE_TYPE);
        writeCause(action, description);
        log.entities().forEach(changes -> writeEntry(action, changes));
    }

    private NodeBuilder bucketFor(final String name)
    {
        NodeBuilder bucket = this.root;
        for (final String segment : PrefixTree.bucketPathFor(ROOT_PATH, name).substring(1).split("/")) {
            bucket = bucket.hasChildNode(segment) ? bucket.getChildNode(segment)
                : created(bucket.child(segment), LOG_TYPE, Log.RESOURCE_TYPE);
        }
        return bucket;
    }

    private static void writeCause(final NodeBuilder node, final ActionDescription description)
    {
        node.setProperty(RESOURCE_SUPER_TYPE_PROPERTY, Content.RESOURCE_TYPE);
        description.cause().forEach(node::setProperty);
        node.setProperty(Action.COMPLETE_PROPERTY, description.complete());
        if (description.occurredAt() != null) {
            node.setProperty(Action.OCCURRED_AT_PROPERTY, description.occurredAt(), Type.DATE);
        }
    }

    private void writeEntry(final NodeBuilder action, final ChangeLog.EntityChanges changes)
    {
        final Subject subject = changes.subject();
        final NodeBuilder entry = created(action.child(subject.uuid()), ENTRY_TYPE, Entry.RESOURCE_TYPE);
        entry.setProperty(RESOURCE_SUPER_TYPE_PROPERTY, Content.RESOURCE_TYPE);
        entry.setProperty(Entry.SUBJECT_PROPERTY, subject.uuid());
        entry.setProperty(Entry.SUBJECT_PATH_PROPERTY, subject.path());
        entry.setProperty(Entry.SUBJECT_TYPE_PROPERTY, subject.type());
        entry.setProperty(Entry.ROLE_PROPERTY, changes.role());
        writeList(entry, Entry.CHANGED_PROPERTY, changes.changed());
        writeList(entry, Entry.ADDED_PROPERTY, changes.added());
        writeList(entry, Entry.REMOVED_PROPERTY, changes.removed());
    }

    private static void writeList(final NodeBuilder node, final String property, final List<String> values)
    {
        if (!values.isEmpty()) {
            node.setProperty(property, values, Type.STRINGS);
        }
    }

    /**
     * Gives a new node its type, its resource type, and the creation stamp its type would otherwise fill in.
     *
     * @param node the new node
     * @param nodeType its primary node type
     * @param resourceType its Sling resource type
     * @return the same node
     */
    private NodeBuilder created(final NodeBuilder node, final String nodeType, final String resourceType)
    {
        node.setProperty(PRIMARY_TYPE_PROPERTY, nodeType, Type.NAME);
        node.setProperty(RESOURCE_TYPE_PROPERTY, resourceType);
        node.setProperty(CREATED_PROPERTY, this.now, Type.DATE);
        node.setProperty(CREATED_BY_PROPERTY, this.writer);
        return node;
    }
}
