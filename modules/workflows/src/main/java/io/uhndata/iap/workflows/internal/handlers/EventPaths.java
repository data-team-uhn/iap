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
package io.uhndata.iap.workflows.internal.handlers;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What an event names by its path in one of its entries, such as where content moves. The entry is read as every
 * handler reads one (see {@link Payloads}): absent, blank or not text, it names nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EventPaths
{
    private EventPaths()
    {
        // Utility class
    }

    /**
     * The resource at the path an event entry gives.
     *
     * @param context the executing task's context
     * @param entry the payload entry holding the path
     * @param purpose what it is named for, as a refusal says it, such as {@code to move into}
     * @return the resource, or {@code null} when the entry names nothing
     * @throws InvalidPayloadException when it names a path where there is nothing
     */
    static Resource resourceAt(final WorkflowTaskContext context, final String entry, final String purpose)
        throws InvalidPayloadException
    {
        final String path = Payloads.text(context.getEvent(), entry);
        if (path == null) {
            return null;
        }
        final Resource resource = context.getResourceResolver().getResource(path);
        if (resource == null) {
            throw new InvalidPayloadException("There is nothing at " + path + " " + purpose);
        }
        return resource;
    }
}
