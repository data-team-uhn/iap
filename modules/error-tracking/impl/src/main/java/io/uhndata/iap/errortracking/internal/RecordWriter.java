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
package io.uhndata.iap.errortracking.internal;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import io.uhndata.iap.errortracking.api.ErrorLoggerService;

/**
 * Writes tallied faults into the repository, one commit for a whole batch.
 *
 * <p>
 * A fault already recorded is updated in place — its count goes up, its samples take in whatever is new — so a loop
 * failing thousands of times leaves one node behind rather than thousands of copies of itself. Nothing here ever
 * deletes: the service user is not even granted the privilege.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RecordWriter
{
    /** The name of the property counting how many times one fault was recorded. */
    static final String OCCURRENCES = "occurrences";

    /** The name of the property holding when a fault was last recorded. */
    static final String LAST_OCCURRENCE = "lastOccurrence";

    /** The subservice name mapped to the service user allowed to record errors. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

    /** The property naming a node's type, which has to be set explicitly when creating through Sling. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /** The property holding a bounded sample of the paths a fault happened to. */
    private static final String SUBJECTS = "subjects";

    /** The property holding a bounded sample of the users a fault happened to. */
    private static final String ACTORS = "actors";

    /** The property holding a bounded sample of the messages one fault was seen with. */
    private static final String MESSAGES = "messages";

    /** Opens the sessions that do the writing. */
    private final ResourceResolverFactory resolverFactory;

    /**
     * Basic constructor.
     *
     * @param resolverFactory the factory to open recording sessions with
     */
    RecordWriter(final ResourceResolverFactory resolverFactory)
    {
        this.resolverFactory = resolverFactory;
    }

    /**
     * Writes a batch of tallied faults, in one commit.
     *
     * @param batch the faults to write, by fingerprint
     * @throws LoginException if the recording service user is not available
     * @throws PersistenceException if the container is missing, or the batch cannot be committed
     */
    void write(final Map<String, PendingError> batch) throws LoginException, PersistenceException
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            final Resource home = resolver.getResource(ErrorLoggerService.LOGGED_ERRORS_PATH);
            if (home == null) {
                throw new PersistenceException(
                    ErrorLoggerService.LOGGED_ERRORS_PATH + " does not exist, so no error can be recorded there");
            }
            for (final Map.Entry<String, PendingError> entry : batch.entrySet()) {
                final Resource known = home.getChild(entry.getKey());
                if (known == null) {
                    resolver.create(home, entry.getKey(), describe(entry.getValue()));
                } else {
                    update(known, entry.getValue());
                }
            }
            resolver.commit();
        }
    }

    /**
     * Turns a newly seen fault into the properties of the node recording it. {@code sling:resourceType},
     * {@code sling:resourceSuperType} and the creation date are left to the node type, which autocreates them.
     *
     * @param pending the fault to describe
     * @return the properties of a new node
     */
    private static Map<String, Object> describe(final PendingError pending)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE, pending.getPrimaryType());
        properties.put(OCCURRENCES, pending.getOccurrences());
        properties.put(LAST_OCCURRENCE, at(pending.getLastSeen()));
        put(properties, "component", pending.getComponent());
        put(properties, "operation", pending.getOperation());
        put(properties, "type", pending.getThrowableType());
        put(properties, "problem", pending.getProblem());
        put(properties, "stackTrace", pending.getStackTrace());
        put(properties, "lastContext", pending.getLastContext());
        putAll(properties, SUBJECTS, pending.getSubjects());
        putAll(properties, ACTORS, pending.getActors());
        putAll(properties, MESSAGES, pending.getMessages());
        return properties;
    }

    /**
     * Notes that an already recorded fault happened again.
     *
     * @param known the node recording the fault
     * @param pending the occurrences to add to it
     * @throws PersistenceException if the node cannot be modified by the recording session
     */
    private static void update(final Resource known, final PendingError pending) throws PersistenceException
    {
        final ModifiableValueMap values = known.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The recorded error at " + known.getPath() + " cannot be updated");
        }
        values.put(OCCURRENCES, values.get(OCCURRENCES, 0L) + pending.getOccurrences());
        values.put(LAST_OCCURRENCE, at(pending.getLastSeen()));
        if (pending.getLastContext() != null) {
            values.put("lastContext", pending.getLastContext());
        }
        mergeSample(values, SUBJECTS, pending.getSubjects(), PendingError.MAX_SUBJECTS);
        mergeSample(values, ACTORS, pending.getActors(), PendingError.MAX_ACTORS);
        mergeSample(values, MESSAGES, pending.getMessages(), PendingError.MAX_MESSAGES);
    }

    /**
     * Folds newly seen sample values into the ones already stored, newest first, dropping the oldest once the sample
     * is full.
     *
     * @param values the properties of the node recording the fault
     * @param property the sample to merge into
     * @param fresh the newly seen values, most recent first
     * @param cap how many values the sample keeps
     */
    private static void mergeSample(final ModifiableValueMap values, final String property, final List<String> fresh,
        final int cap)
    {
        if (fresh.isEmpty()) {
            return;
        }
        final SequencedSet<String> merged = new LinkedHashSet<>(fresh);
        merged.addAll(List.of(values.get(property, new String[0])));
        final List<String> capped = new ArrayList<>(merged).subList(0, Math.min(merged.size(), cap));
        values.put(property, capped.toArray(new String[0]));
    }

    /**
     * Adds a property, leaving it out entirely when there is nothing to say — an absent property reads better than an
     * empty one, and the node type allows both.
     *
     * @param properties the properties under construction
     * @param name the property to set
     * @param value its value, ignored when {@code null}
     */
    private static void put(final Map<String, Object> properties, final String name, final String value)
    {
        if (value != null) {
            properties.put(name, value);
        }
    }

    /**
     * Adds a multi-valued property, leaving it out entirely when the sample is empty.
     *
     * @param properties the properties under construction
     * @param name the property to set
     * @param values its values, ignored when empty
     */
    private static void putAll(final Map<String, Object> properties, final String name, final List<String> values)
    {
        if (!values.isEmpty()) {
            properties.put(name, values.toArray(new String[0]));
        }
    }

    /**
     * A JCR date for a moment in time.
     *
     * @param moment milliseconds since the epoch
     * @return the same moment as a calendar
     */
    private static Calendar at(final long moment)
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(moment);
        return calendar;
    }
}
