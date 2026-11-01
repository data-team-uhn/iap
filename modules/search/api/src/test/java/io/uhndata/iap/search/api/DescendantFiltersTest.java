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
package io.uhndata.iap.search.api;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DescendantFilters}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DescendantFiltersTest
{
    @Test
    public void keepsItsConditionsAsTheyWereGiven()
    {
        final List<Filter> given = new ArrayList<>(List.of(new Filter("question", "=", "q1")));
        final DescendantFilters family = new DescendantFilters("sub:Answer", given);
        given.clear();

        Assertions.assertEquals("sub:Answer", family.type());
        Assertions.assertEquals(1, family.filters().size());
    }

    @Test
    public void refusesAMissingTypeOrConditionList()
    {
        Assertions.assertThrows(NullPointerException.class, () -> new DescendantFilters(null, List.of()));
        Assertions.assertThrows(NullPointerException.class, () -> new DescendantFilters("sub:Answer", null));
    }
}
