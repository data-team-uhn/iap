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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link DeletionResult}.
 *
 * @version $Id$
 */
class DeletionResultTest
{
    private static final DeletionImpact IMPACT =
        new DeletionImpact(List.of("/a"), List.of(), List.of(), List.of(), 0, "");

    @Test
    void valuesAreKept()
    {
        final DeletionResult archived =
            new DeletionResult(DeletionResult.Status.ARCHIVED, "/Archive/123", IMPACT);
        assertEquals(DeletionResult.Status.ARCHIVED, archived.getStatus());
        assertEquals("/Archive/123", archived.getArchiveEntryPath());
        assertSame(IMPACT, archived.getImpact());
        assertNull(new DeletionResult(DeletionResult.Status.DELETED, null, IMPACT).getArchiveEntryPath());
    }

    @Test
    void statusEnumIsComplete()
    {
        assertEquals(5, DeletionResult.Status.values().length);
        assertEquals(DeletionResult.Status.VETOED, DeletionResult.Status.valueOf("VETOED"));
    }
}
