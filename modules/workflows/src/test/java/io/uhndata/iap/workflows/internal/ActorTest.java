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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Actor}: an action counts as whoever it was done for, and as its sender only when it was done for
 * nobody else.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ActorTest
{
    @Test
    void countsAsTheSenderWhenActingForThemselves()
    {
        final Actor actor = Actor.of("alice");

        Assertions.assertEquals("alice", actor.effectiveUser(), "Acting for nobody else counts as the sender");
        Assertions.assertNull(actor.onBehalfOf(), "Acting for oneself records nobody else");
    }

    @Test
    void countsAsWhoeverTheActionWasDoneFor()
    {
        final Actor actor = new Actor("admin", "alice");

        Assertions.assertEquals("alice", actor.effectiveUser(), "Acting for someone counts as them");
        Assertions.assertEquals("admin", actor.id(), "The sender stays answerable");
    }
}
