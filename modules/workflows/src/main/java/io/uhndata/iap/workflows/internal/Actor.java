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

import java.util.Objects;

/**
 * Who is moving an execution: the user who sent the event, and whom they sent it for, if anyone else.
 *
 * @param sender the canonical id of the user who sent the event, answerable for it
 * @param onBehalfOf the canonical id of whom they acted for, or {@code null} when they acted for themselves
 * @version $Id$
 * @since 0.1.0
 */
record Actor(String sender, String onBehalfOf)
{
    /**
     * Acting for yourself is acting for nobody else, so it has one shape: history must never show someone acting on
     * their own behalf.
     *
     * @param sender the canonical id of the user who sent the event
     * @param onBehalfOf the canonical id of whom they acted for, or {@code null}
     */
    Actor
    {
        onBehalfOf = Objects.equals(sender, onBehalfOf) ? null : onBehalfOf;
    }

    /**
     * A user acting for themselves.
     *
     * @param sender the user's canonical id
     * @return an actor acting for nobody else
     */
    static Actor of(final String sender)
    {
        return new Actor(sender, null);
    }

    /**
     * The user the event counts as: whom it was sent for, or the sender when they acted for themselves.
     *
     * @return a canonical user id
     */
    String effectiveUser()
    {
        return this.onBehalfOf == null ? this.sender : this.onBehalfOf;
    }
}
