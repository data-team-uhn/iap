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
package io.uhndata.iap.extraction.internal;

import java.lang.reflect.Field;

import org.apache.sling.event.jobs.JobManager;

/**
 * Wires the module's components by hand. The tests run without OSGi, so nothing fills their references.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestComponents
{
    private TestComponents()
    {
        // Utility class
    }

    /**
     * Sets a component's reference, as OSGi would.
     *
     * @param component the component to fill
     * @param name the name of the reference's field
     * @param value what the reference points at
     */
    static void inject(final Object component, final String name, final Object value)
    {
        try {
            final Field field = component.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(component, value);
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates the checks and queue shared by the handlers that start a reading.
     *
     * @param jobManager where the reading's jobs are queued
     * @param chain the decision chain, shared with the handler under test when it reads the chain too
     * @return the wired requests
     */
    static ReadingRequests createRequests(final JobManager jobManager, final DecisionChain chain)
    {
        final ReadingQueue queue = new ReadingQueue();
        inject(queue, "jobManager", jobManager);
        final ReadingRequests requests = new ReadingRequests();
        inject(requests, "readingQueue", queue);
        inject(requests, "chain", chain);
        inject(requests, "formsToRead", new FormsToRead());
        return requests;
    }
}
