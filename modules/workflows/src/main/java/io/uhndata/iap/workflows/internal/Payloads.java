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
package io.uhndata.iap.workflows.internal;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;

/**
 * Reads one payload entry the way every handler wants to read it: an absent, blank, or non-string value is the
 * same refusal.
 *
 * <p>Blank counts as absent because an empty form field arrives as an empty string, not as a missing one — from
 * the caller's perspective it's the same mistake either way.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Payloads
{
    private Payloads()
    {
    }

    /**
     * One text entry of the payload, if it carries a usable one.
     *
     * @param event the event being handled
     * @param name the payload entry to read
     * @return the trimmed value, or {@code null} if it is absent, blank, or not text
     */
    static String text(final WorkflowEvent event, final String name)
    {
        final Object value = event.get(name);
        if (!(value instanceof String)) {
            return null;
        }
        final String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One text entry of the payload that the handler cannot do without.
     *
     * @param event the event being handled
     * @param name the payload entry to read
     * @param complaint what to tell the caller if it is missing, phrased for a person
     * @return the trimmed value
     * @throws InvalidPayloadException if it is absent, blank, or not text
     */
    static String requireText(final WorkflowEvent event, final String name, final String complaint)
        throws InvalidPayloadException
    {
        final String value = text(event, name);
        if (value == null) {
            throw new InvalidPayloadException(complaint);
        }
        return value;
    }

    /**
     * One uploaded file of the payload, if it carries one under that name.
     *
     * @param event the event being handled
     * @param name the payload entry to read
     * @return the attachment, or {@code null} if nothing arrived under that name, or what did is not a file
     */
    static EventAttachment attachment(final WorkflowEvent event, final String name)
    {
        final Object value = event.get(name);
        return value instanceof EventAttachment ? (EventAttachment) value : null;
    }
}
