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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RestoreResult} and {@link RestoreConflict}.
 *
 * @version $Id$
 */
class RestoreResultTest
{
    @Test
    void valuesAreKept()
    {
        final RestoreConflict conflict = new RestoreConflict("/a", RestoreConflict.Reason.OCCUPIED);
        assertEquals("/a", conflict.getOriginalPath());
        assertEquals(RestoreConflict.Reason.OCCUPIED, conflict.getReason());
        final RestoreResult conflicted =
            new RestoreResult(RestoreResult.Status.CONFLICT, List.of(), List.of(conflict));
        assertEquals(RestoreResult.Status.CONFLICT, conflicted.getStatus());
        assertTrue(conflicted.getRestoredPaths().isEmpty());
        assertEquals(1, conflicted.getConflicts().size());
        final RestoreResult restored = new RestoreResult(RestoreResult.Status.RESTORED, List.of("/a"), List.of());
        assertEquals(List.of("/a"), restored.getRestoredPaths());
    }

    @Test
    void enumsAreComplete()
    {
        assertEquals(2, RestoreResult.Status.values().length);
        assertEquals(RestoreResult.Status.RESTORED, RestoreResult.Status.valueOf("RESTORED"));
        assertEquals(3, RestoreConflict.Reason.values().length);
        assertEquals(RestoreConflict.Reason.PARENT_MISSING, RestoreConflict.Reason.valueOf("PARENT_MISSING"));
    }
}
