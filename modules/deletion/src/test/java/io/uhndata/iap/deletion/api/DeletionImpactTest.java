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
package io.uhndata.iap.deletion.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeletionImpact}.
 *
 * @version $Id$
 */
class DeletionImpactTest
{
    private static final Veto VETO = new Veto("guard", "/protected", "no");

    private static final ReferrerGroup GROUP = new ReferrerGroup("sub:Submission", "submission", List.of("S-1"), 1);

    @Test
    void valuesAreKept()
    {
        final DeletionImpact impact = new DeletionImpact(List.of("/a"), List.of("/b/iap:links/l"),
            List.of(VETO), List.of(GROUP), 2, "This item is referenced by things.");
        assertEquals(List.of("/a"), impact.getItemPaths());
        assertEquals(List.of("/b/iap:links/l"), impact.getRemovedLinkPaths());
        assertEquals(1, impact.getVetoes().size());
        assertEquals(1, impact.getReferrers().size());
        assertEquals(2, impact.getInaccessibleReferrerCount());
        assertEquals("This item is referenced by things.", impact.getSummary());
    }

    @Test
    void executableOnlyWithoutBlockers()
    {
        assertTrue(new DeletionImpact(List.of("/a"), List.of(), List.of(), List.of(), 0, "").isExecutable());
        assertFalse(new DeletionImpact(List.of("/a"), List.of(), List.of(VETO), List.of(), 0, "").isExecutable());
        assertFalse(new DeletionImpact(List.of("/a"), List.of(), List.of(), List.of(GROUP), 0, "").isExecutable());
        assertFalse(new DeletionImpact(List.of("/a"), List.of(), List.of(), List.of(), 1, "").isExecutable());
    }
}
