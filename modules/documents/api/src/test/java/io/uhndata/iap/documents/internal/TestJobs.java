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
package io.uhndata.iap.documents.internal;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * What the documents tests share: a job record as the dispatch leaves it, and wiring an {@code @Reference} by hand.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestJobs
{
    private TestJobs()
    {
        // Static helpers only
    }

    /**
     * Creates a job record holding its identifier, status, staged document, and target if any.
     *
     * @param context the test context to create the record in
     * @param jobId the job's identifier
     * @param status the job's status
     * @param path the staged document
     * @param target what waits on the parse, {@code null} for a job only polled
     * @return the new record
     */
    static Resource createJob(final SlingContext context, final String jobId, final String status, final String path,
        final String target)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParseJob.PN_JOB_ID, jobId);
        properties.put(ParseJob.PN_STATUS, status);
        properties.put(ParseJob.PN_PATH, path);
        if (target != null) {
            properties.put(ParseJob.PN_TARGET, target);
        }
        return context.create().resource(ParseJob.nodePath(jobId), properties);
    }

    /**
     * Sets a field the component framework would inject, on the target's class or the class it extends.
     *
     * @param target the component, possibly an anonymous subclass of it
     * @param name the field to set
     * @param value the value to set it to
     * @throws ReflectiveOperationException when no such field exists
     */
    static void inject(final Object target, final String name, final Object value) throws ReflectiveOperationException
    {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
