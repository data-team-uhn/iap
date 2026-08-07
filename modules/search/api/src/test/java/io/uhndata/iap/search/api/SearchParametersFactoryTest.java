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
 * Unit tests for {@link SearchParametersFactory}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SearchParametersFactoryTest
{
    private static final String QUERY = "diabetes";

    private static final List<String> TYPES = List.of("sub:Submission");

    @Test
    public void allTheConfigurationIsCarriedOver()
    {
        final SearchParameters parameters = SearchParametersFactory.newSearchParameters()
            .withQuery(QUERY)
            .withResourceTypes(TYPES)
            .withMaxResults(25)
            .build();
        Assertions.assertEquals(QUERY, parameters.getQuery());
        Assertions.assertEquals(TYPES, parameters.getResourceTypes());
        Assertions.assertEquals(25, parameters.getMaxResults());
    }

    @Test
    public void maxResultsHasADefault()
    {
        final SearchParameters parameters = SearchParametersFactory.newSearchParameters()
            .withQuery(QUERY).withResourceTypes(TYPES).build();
        Assertions.assertEquals(SearchParametersFactory.DEFAULT_MAX_RESULTS, parameters.getMaxResults());
    }

    @Test
    public void aQueryIsRequired()
    {
        final SearchParametersFactory factory = SearchParametersFactory.newSearchParameters().withResourceTypes(TYPES);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withQuery("  ");
        Assertions.assertThrows(IllegalStateException.class, factory::build);
    }

    @Test
    public void resourceTypesAreRequired()
    {
        final SearchParametersFactory factory = SearchParametersFactory.newSearchParameters().withQuery(QUERY);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withResourceTypes(null);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withResourceTypes(List.of());
        Assertions.assertThrows(IllegalStateException.class, factory::build);
    }

    @Test
    public void maxResultsMustBePositive()
    {
        final SearchParametersFactory factory = SearchParametersFactory.newSearchParameters();
        Assertions.assertThrows(IllegalArgumentException.class, () -> factory.withMaxResults(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> factory.withMaxResults(-1));
    }

    @Test
    public void reconfiguringTheFactoryLeavesBuiltParametersAlone()
    {
        final SearchParametersFactory factory =
            SearchParametersFactory.newSearchParameters().withQuery(QUERY).withResourceTypes(TYPES);
        final SearchParameters first = factory.build();
        factory.withQuery("other").withResourceTypes(List.of("sch:Schema")).withMaxResults(1);
        Assertions.assertEquals(QUERY, first.getQuery());
        Assertions.assertEquals(TYPES, first.getResourceTypes());
        Assertions.assertEquals(SearchParametersFactory.DEFAULT_MAX_RESULTS, first.getMaxResults());
    }

    @Test
    public void resourceTypesAreCopiedAndImmutable()
    {
        final List<String> types = new ArrayList<>(TYPES);
        final SearchParameters parameters = SearchParametersFactory.newSearchParameters()
            .withQuery(QUERY).withResourceTypes(types).build();
        types.add("sch:Schema");
        Assertions.assertEquals(TYPES, parameters.getResourceTypes());
        Assertions.assertThrows(UnsupportedOperationException.class,
            () -> parameters.getResourceTypes().add("sch:Schema"));
    }
}
