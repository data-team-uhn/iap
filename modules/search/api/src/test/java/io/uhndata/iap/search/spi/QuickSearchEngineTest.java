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
package io.uhndata.iap.search.spi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.search.api.SearchParameters;

/**
 * Unit tests for the default behaviour of {@link QuickSearchEngine}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class QuickSearchEngineTest
{
    @Test
    public void supportedTypesDecideWhichEngineIsAsked()
    {
        final QuickSearchEngine engine = new StubEngine(List.of("sub:Submission", "sch:Schema"));
        Assertions.assertTrue(engine.isTypeSupported("sub:Submission"));
        Assertions.assertTrue(engine.isTypeSupported("sch:Schema"));
        Assertions.assertFalse(engine.isTypeSupported("iap:TagDefinition"));
    }

    @Test
    public void thereAreNoEmptyResultsToRead()
    {
        final QuickSearchEngine.Results empty = QuickSearchEngine.Results.empty();
        Assertions.assertFalse(empty.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, empty::next);
        // Skipping nothing is not an error
        Assertions.assertDoesNotThrow(empty::skip);
        Assertions.assertDoesNotThrow(empty::close);
    }

    @Test
    public void anEngineWithNothingToReleaseNeedNotSaySo()
    {
        // The caller closes every result set, including the ones from engines that never opened anything, so the
        // default has to be a no-op rather than something an implementation is obliged to write
        final QuickSearchEngine.Results results = new StubEngine(List.of()).quickSearch(null, null);
        Assertions.assertDoesNotThrow(results::close);
        // Closing changes nothing for an engine that holds nothing
        Assertions.assertTrue(results.hasNext());
    }

    @Test
    public void skippingDefaultsToReadingAndDiscarding()
    {
        final QuickSearchEngine.Results results =
            new StubEngine(List.of()).quickSearch(null, null);
        results.skip();
        Assertions.assertEquals("second", results.next().getString("name"));
        Assertions.assertFalse(results.hasNext());
    }

    /** An engine returning two fixed results, using the default {@code skip()}. */
    private static final class StubEngine implements QuickSearchEngine
    {
        private final List<String> types;

        StubEngine(final List<String> types)
        {
            this.types = types;
        }

        @Override
        public List<String> getSupportedTypes()
        {
            return this.types;
        }

        @Override
        public Results quickSearch(final SearchParameters query, final ResourceResolver resourceResolver)
        {
            final Deque<String> names = new ArrayDeque<>(List.of("first", "second"));
            return new Results()
            {
                @Override
                public boolean hasNext()
                {
                    return !names.isEmpty();
                }

                @Override
                public JsonObject next()
                {
                    return Json.createObjectBuilder().add("name", names.removeFirst()).build();
                }
            };
        }
    }
}
