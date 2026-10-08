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

/**
 * Who is moving an execution: the user who sent the event, and whom they sent it for, if anyone else.
 *
 * @param id the canonical id of the user who sent the event, answerable for it
 * @param onBehalfOf the canonical id of whom they acted for, or {@code null} when they acted for themselves
 * @version $Id$
 * @since 0.1.0
 */
record Actor(String id, String onBehalfOf)
{
    /**
     * A user acting for themselves.
     *
     * @param id the user's canonical id
     * @return an actor acting for nobody else
     */
    static Actor of(final String id)
    {
        return new Actor(id, null);
    }

    /**
     * The user the action counts as (either the person who sent the action or whom it was done for).
     *
     * @return a canonical user id
     */
    String effectiveUser()
    {
        return this.onBehalfOf == null ? this.id : this.onBehalfOf;
    }
}
