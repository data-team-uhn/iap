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
package io.uhndata.iap.llm.internal;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link BoundedCache}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class BoundedCacheTest
{
    private final AtomicInteger builds = new AtomicInteger();

    private String build(final String key)
    {
        this.builds.incrementAndGet();
        return key.toUpperCase(Locale.ROOT);
    }

    @Test
    void buildsAValueOnceAndReusesIt()
    {
        final BoundedCache<String, String> cache = new BoundedCache<>(2);

        assertEquals("A", cache.get("a", this::build));
        assertEquals("A", cache.get("a", this::build));

        assertEquals(1, this.builds.get());
        assertEquals(1, cache.size());
    }

    @Test
    void keepsNoMoreThanItsCapacity()
    {
        final BoundedCache<String, String> cache = new BoundedCache<>(2);

        cache.get("a", this::build);
        cache.get("b", this::build);
        cache.get("c", this::build);

        assertEquals(2, cache.size());
    }

    @Test
    void dropsTheValueUsedLeastRecently()
    {
        final BoundedCache<String, String> cache = new BoundedCache<>(2);
        cache.get("a", this::build);
        cache.get("b", this::build);
        cache.get("a", this::build);

        cache.get("c", this::build);

        assertEquals(3, this.builds.get());
        cache.get("a", this::build);
        assertEquals(3, this.builds.get(), "a was used after b, so it stayed");
        cache.get("b", this::build);
        assertEquals(4, this.builds.get(), "b was the one dropped");
    }

    @Test
    void treatsACapacityBelowOneAsOne()
    {
        final BoundedCache<String, String> cache = new BoundedCache<>(0);

        cache.get("a", this::build);
        cache.get("b", this::build);

        assertEquals(1, cache.size());
    }
}
