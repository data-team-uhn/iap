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

import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link SearchContextFactory}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SearchContextFactoryTest
{
    private static final String QUERY = "diabetes";

    private static final List<String> TYPES = List.of("sub:Submission");

    private ResourceResolver resolver;

    @BeforeEach
    public void setup()
    {
        this.resolver = Mockito.mock(ResourceResolver.class);
    }

    @Test
    public void allTheConfigurationIsCarriedOver()
    {
        final SearchContext context = SearchContextFactory.newSearchContext()
            .withQuery(QUERY)
            .withResourceTypes(TYPES)
            .withMaxResults(25)
            .withResourceResolver(this.resolver)
            .build();
        Assertions.assertEquals(QUERY, context.getQuery());
        Assertions.assertEquals(TYPES, context.getResourceTypes());
        Assertions.assertEquals(25, context.getMaxResults());
        Assertions.assertSame(this.resolver, context.getResourceResolver());
    }

    @Test
    public void maxResultsHasADefault()
    {
        final SearchContext context = complete().build();
        Assertions.assertEquals(SearchContextFactory.DEFAULT_MAX_RESULTS, context.getMaxResults());
    }

    @Test
    public void aQueryIsRequired()
    {
        final SearchContextFactory factory = SearchContextFactory.newSearchContext()
            .withResourceTypes(TYPES).withResourceResolver(this.resolver);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withQuery("  ");
        Assertions.assertThrows(IllegalStateException.class, factory::build);
    }

    @Test
    public void resourceTypesAreRequired()
    {
        final SearchContextFactory factory = SearchContextFactory.newSearchContext()
            .withQuery(QUERY).withResourceResolver(this.resolver);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withResourceTypes(null);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withResourceTypes(List.of());
        Assertions.assertThrows(IllegalStateException.class, factory::build);
    }

    @Test
    public void aResourceResolverIsRequired()
    {
        // Without one, an engine has nothing to read through
        final SearchContextFactory factory =
            SearchContextFactory.newSearchContext().withQuery(QUERY).withResourceTypes(TYPES);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
        factory.withResourceResolver(null);
        Assertions.assertThrows(IllegalStateException.class, factory::build);
    }

    @Test
    public void maxResultsMustBePositive()
    {
        final SearchContextFactory factory = SearchContextFactory.newSearchContext();
        Assertions.assertThrows(IllegalArgumentException.class, () -> factory.withMaxResults(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> factory.withMaxResults(-1));
    }

    @Test
    public void reconfiguringTheFactoryLeavesBuiltContextsAlone()
    {
        final SearchContextFactory factory = complete();
        final SearchContext first = factory.build();
        factory.withQuery("other").withResourceTypes(List.of("sch:Schema")).withMaxResults(1)
            .withResourceResolver(Mockito.mock(ResourceResolver.class));
        Assertions.assertEquals(QUERY, first.getQuery());
        Assertions.assertEquals(TYPES, first.getResourceTypes());
        Assertions.assertEquals(SearchContextFactory.DEFAULT_MAX_RESULTS, first.getMaxResults());
        Assertions.assertSame(this.resolver, first.getResourceResolver());
    }

    @Test
    public void resourceTypesAreCopiedAndImmutable()
    {
        final List<String> types = new ArrayList<>(TYPES);
        final SearchContext context = SearchContextFactory.newSearchContext()
            .withQuery(QUERY).withResourceTypes(types).withResourceResolver(this.resolver).build();
        types.add("sch:Schema");
        Assertions.assertEquals(TYPES, context.getResourceTypes());
        Assertions.assertThrows(UnsupportedOperationException.class,
            () -> context.getResourceTypes().add("sch:Schema"));
    }

    /** A factory with everything {@link SearchContextFactory#build()} insists on already set. */
    private SearchContextFactory complete()
    {
        return SearchContextFactory.newSearchContext()
            .withQuery(QUERY).withResourceTypes(TYPES).withResourceResolver(this.resolver);
    }
}
