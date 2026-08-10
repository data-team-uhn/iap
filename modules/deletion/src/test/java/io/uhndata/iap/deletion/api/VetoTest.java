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

/**
 * Tests for {@link Veto} and {@link ReferrerGroup}.
 *
 * @version $Id$
 */
class VetoTest
{
    @Test
    void vetoValuesAreKept()
    {
        final Veto veto = new Veto("undeletable", "/Workflows/x/1.0", "This resource is protected from deletion");
        assertEquals("undeletable", veto.getVetoerName());
        assertEquals("/Workflows/x/1.0", veto.getPath());
        assertEquals("This resource is protected from deletion", veto.getReason());
    }

    @Test
    void referrerGroupValuesAreKept()
    {
        final ReferrerGroup group =
            new ReferrerGroup("sub:Submission", "submission", List.of("S-1", "S-2"), 5);
        assertEquals("sub:Submission", group.getNodeType());
        assertEquals("submission", group.getLabel());
        assertEquals(List.of("S-1", "S-2"), group.getNames());
        assertEquals(5, group.getCount());
    }
}
