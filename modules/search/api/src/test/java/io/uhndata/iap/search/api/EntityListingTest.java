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
 * Unit tests for {@link EntityListing}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class EntityListingTest
{
    private static final String SCOPE = "/Submissions";

    private static final String TYPE = "sub:Submission";

    private static final String SORT = "jcr:created";

    @Test
    public void keepsItsConditionsAsTheyWereGiven()
    {
        final List<Filter> filters = new ArrayList<>(List.of(new Filter("tags", "=", "draft")));
        final List<DescendantFilters> descendants =
            new ArrayList<>(List.of(new DescendantFilters("sub:Review", List.of())));
        final EntityListing listing = new EntityListing(SCOPE, TYPE, filters, descendants, "budget", SORT, true);
        filters.clear();
        descendants.clear();

        Assertions.assertEquals(1, listing.filters().size());
        Assertions.assertEquals(1, listing.descendants().size());
        Assertions.assertEquals("budget", listing.fullText());
        Assertions.assertTrue(listing.descending());
    }

    @Test
    public void refusesAMissingScopeTypeOrderOrConditionList()
    {
        final List<Filter> none = List.of();
        final List<DescendantFilters> noDescendants = List.of();
        Assertions.assertThrows(NullPointerException.class,
            () -> new EntityListing(null, TYPE, none, noDescendants, null, SORT, false));
        Assertions.assertThrows(NullPointerException.class,
            () -> new EntityListing(SCOPE, null, none, noDescendants, null, SORT, false));
        Assertions.assertThrows(NullPointerException.class,
            () -> new EntityListing(SCOPE, TYPE, none, noDescendants, null, null, false));
        Assertions.assertThrows(NullPointerException.class,
            () -> new EntityListing(SCOPE, TYPE, null, noDescendants, null, SORT, false));
        Assertions.assertThrows(NullPointerException.class,
            () -> new EntityListing(SCOPE, TYPE, none, null, null, SORT, false));
    }
}
