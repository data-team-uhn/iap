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
 * Writes tallied faults into the repository, one commit per batch.
 *
 * <p>
 * A fault already recorded is updated in place: its count goes up, and its samples take in the new values. A loop
 * that fails a thousand times leaves one node. Nothing here deletes, and the service user may not.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RecordWriter
{
    /** The property counting how many times a fault was recorded. */
    static final String OCCURRENCES_PROPERTY = "occurrences";

    /** The property holding when a fault was last recorded. */
    static final String LAST_OCCURRENCE = "lastOccurrence";

    /** The service user that records errors, as a subservice name. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

    /** The property naming a node's type, which Sling needs when it creates a node. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /** The property holding a sample of the paths a fault happened to. */
    private static final String SUBJECTS_PROPERTY = "subjects";

    /** The property holding a sample of the usernames a fault happened on behalf of. */
    private static final String ACTORS_PROPERTY = "actors";

    /** The property holding a sample of the messages a fault was seen with. */
    private static final String MESSAGES_PROPERTY = "messages";

    /** Opens the sessions that write. */
    private final ResourceResolverFactory resolverFactory;

    /**
     * Basic constructor.
     *
     * @param resolverFactory the factory to open the service user's sessions with
     */
    RecordWriter(final ResourceResolverFactory resolverFactory)
    {
        this.resolverFactory = resolverFactory;
    }

    /**
     * Writes a batch of tallied faults, in one commit.
     *
     * @param batch the tallies to write, by fingerprint
     * @throws LoginException if the service user cannot log in
     * @throws PersistenceException if {@value ErrorLoggerService#LOGGED_ERRORS_PATH} is missing, a recorded error
     *             cannot be modified, or the batch cannot be committed
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
     * The properties of the node that records a new fault. The node type autocreates {@code sling:resourceType},
     * {@code sling:resourceSuperType} and {@code jcr:created}.
     *
     * @param pending the tally of the fault
     * @return the properties of the new node, leaving out every value that is {@code null} or empty
     */
    private static Map<String, Object> describe(final PendingError pending)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE, pending.getPrimaryType());
        properties.put(OCCURRENCES_PROPERTY, pending.getOccurrences());
        properties.put(LAST_OCCURRENCE, at(pending.getLastSeen()));
        put(properties, "component", pending.getComponent());
        put(properties, "operation", pending.getOperation());
        put(properties, "type", pending.getThrowableType());
        put(properties, "problem", pending.getProblem());
        put(properties, "stackTrace", pending.getStackTrace());
        put(properties, "lastContext", pending.getLastContext());
        putAll(properties, SUBJECTS_PROPERTY, pending.getSubjects());
        putAll(properties, ACTORS_PROPERTY, pending.getActors());
        putAll(properties, MESSAGES_PROPERTY, pending.getMessages());
        return properties;
    }

    /**
     * Adds a tally to the node that already records its fault. The node's details are replaced only if the tally has
     * some.
     *
     * @param known the node that records the fault
     * @param pending the tally to add
     * @throws PersistenceException if the service user's session cannot modify the node
     */
    private static void update(final Resource known, final PendingError pending) throws PersistenceException
    {
        final ModifiableValueMap values = known.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The recorded error at " + known.getPath() + " cannot be updated");
        }
        values.put(OCCURRENCES_PROPERTY, values.get(OCCURRENCES_PROPERTY, 0L) + pending.getOccurrences());
        values.put(LAST_OCCURRENCE, at(pending.getLastSeen()));
        if (pending.getLastContext() != null) {
            values.put("lastContext", pending.getLastContext());
        }
        mergeSample(values, SUBJECTS_PROPERTY, pending.getSubjects(), PendingError.MAX_SUBJECTS);
        mergeSample(values, ACTORS_PROPERTY, pending.getActors(), PendingError.MAX_ACTORS);
        mergeSample(values, MESSAGES_PROPERTY, pending.getMessages(), PendingError.MAX_MESSAGES);
    }

    /**
     * Puts new sample values before the stored ones, and drops the oldest beyond the cap.
     *
     * @param values the properties of the node that records the fault
     * @param property the name of the sample property
     * @param fresh the new values, most recent first
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
     * Adds a property, unless its value is {@code null}.
     *
     * @param properties the properties being built
     * @param name the name of the property
     * @param value its value; ignored if {@code null}
     */
    private static void put(final Map<String, Object> properties, final String name, final String value)
    {
        if (value != null) {
            properties.put(name, value);
        }
    }

    /**
     * Adds a multi-valued property, unless it has no values.
     *
     * @param properties the properties being built
     * @param name the name of the property
     * @param values its values; ignored if empty
     */
    private static void putAll(final Map<String, Object> properties, final String name, final List<String> values)
    {
        if (!values.isEmpty()) {
            properties.put(name, values.toArray(new String[0]));
        }
    }

    /**
     * A JCR date for a moment.
     *
     * @param moment milliseconds since the epoch
     * @return the same moment as a calendar, in the JVM's time zone
     */
    private static Calendar at(final long moment)
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(moment);
        return calendar;
    }
}
