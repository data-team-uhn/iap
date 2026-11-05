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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What one commit did to each entity, gathered while the commit is walked. There is one {@link EntityChanges} per
 * entity, found by its {@code jcr:uuid}, so an entity moved within the commit is one entity removed and added again.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ChangeLog
{
    private final Map<String, EntityChanges> entities = new LinkedHashMap<>();

    /**
     * Notes a property that changed inside an entity.
     *
     * @param subject the entity
     * @param path the property's path relative to the entity
     */
    void noteChanged(final Subject subject, final String path)
    {
        of(subject).changed.add(path);
    }

    /**
     * Notes a branch added inside an entity.
     *
     * @param subject the entity
     * @param path the path of the branch's topmost node, relative to the entity
     */
    void noteAdded(final Subject subject, final String path)
    {
        of(subject).added.add(path);
    }

    /**
     * Notes a branch removed from inside an entity.
     *
     * @param subject the entity
     * @param path the path the branch's topmost node had, relative to the entity
     */
    void noteRemoved(final Subject subject, final String path)
    {
        of(subject).removed.add(path);
    }

    /**
     * Notes an entity the commit created.
     *
     * @param subject the entity, where it now is
     */
    void noteCreated(final Subject subject)
    {
        final EntityChanges changes = of(subject);
        changes.created = true;
        changes.subject = subject;
    }

    /**
     * Notes an entity the commit removed.
     *
     * @param subject the entity, where it was
     */
    void noteDeleted(final Subject subject)
    {
        of(subject).deleted = true;
    }

    /**
     * Every entity the commit affected.
     *
     * @return their changes, in the order each entity was first met
     */
    Collection<EntityChanges> entities()
    {
        return this.entities.values();
    }

    private EntityChanges of(final Subject subject)
    {
        return this.entities.computeIfAbsent(subject.uuid(), uuid -> new EntityChanges(subject));
    }

    /**
     * What a commit did to one entity.
     *
     * @version $Id$
     * @since 0.1.0
     */
    static final class EntityChanges
    {
        private final SortedSet<String> changed = new TreeSet<>();

        private final SortedSet<String> added = new TreeSet<>();

        private final SortedSet<String> removed = new TreeSet<>();

        private Subject subject;

        private boolean created;

        private boolean deleted;

        private EntityChanges(final Subject subject)
        {
            this.subject = subject;
        }

        /**
         * The entity, where it is after the commit, or where it was if the commit removed it.
         *
         * @return the entity
         */
        Subject subject()
        {
            return this.subject;
        }

        /**
         * The part the entity played, from what the commit did to it.
         *
         * @return the role
         */
        String role()
        {
            if (this.created && this.deleted) {
                return "moved";
            } else if (this.created) {
                return "created";
            } else if (this.deleted) {
                return "deleted";
            }
            return "updated";
        }

        /**
         * The properties that changed inside the entity.
         *
         * @return their paths relative to the entity, sorted
         */
        List<String> changed()
        {
            return List.copyOf(this.changed);
        }

        /**
         * The branches added inside the entity.
         *
         * @return the paths of their topmost nodes relative to the entity, sorted
         */
        List<String> added()
        {
            return List.copyOf(this.added);
        }

        /**
         * The branches removed from inside the entity.
         *
         * @return the paths their topmost nodes had relative to the entity, sorted
         */
        List<String> removed()
        {
            return List.copyOf(this.removed);
        }
    }
}
