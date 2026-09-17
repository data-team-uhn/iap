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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps the most recently used values, up to a fixed number.
 *
 * <p>Building happens under the lock, so two calls that miss at the same moment build one value, not two.
 * Everything cached here is cheap to build, so holding the lock for it costs nothing worth avoiding.
 *
 * @param <K> the key type
 * @param <V> the value type
 * @version $Id$
 * @since 0.1.0
 */
final class BoundedCache<K, V>
{
    private final int capacity;

    /** Access-ordered, so the first key is the one used least recently. */
    private final Map<K, V> values = new LinkedHashMap<>(16, 0.75f, true);

    BoundedCache(final int capacity)
    {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * The value for a key, building it when there is none yet.
     *
     * @param key the key
     * @param build how to build a missing value
     * @return the value
     */
    synchronized V get(final K key, final Function<K, V> build)
    {
        final V value = this.values.computeIfAbsent(key, build);
        if (this.values.size() > this.capacity) {
            this.values.remove(this.values.keySet().iterator().next());
        }
        return value;
    }

    /**
     * How many values are kept right now.
     *
     * @return the count
     */
    synchronized int size()
    {
        return this.values.size();
    }
}
