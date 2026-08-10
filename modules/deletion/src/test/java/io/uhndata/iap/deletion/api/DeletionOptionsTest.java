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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeletionOptions}.
 *
 * @version $Id$
 */
class DeletionOptionsTest
{
    @Test
    void archiveIsNeitherRecursiveNorPermanent()
    {
        final DeletionOptions options = DeletionOptions.archive();
        assertFalse(options.isRecursive());
        assertFalse(options.isPermanent());
    }

    @Test
    void explicitOptionsAreKept()
    {
        final DeletionOptions options = DeletionOptions.of(true, true);
        assertTrue(options.isRecursive());
        assertTrue(options.isPermanent());
        assertFalse(DeletionOptions.of(false, true).isRecursive());
        assertFalse(DeletionOptions.of(true, false).isPermanent());
    }
}
