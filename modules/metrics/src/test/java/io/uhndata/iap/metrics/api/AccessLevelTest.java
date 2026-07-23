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
package io.uhndata.iap.metrics.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link Metric.AccessLevel}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AccessLevelTest
{
    @Test
    void serializesAsLowercase()
    {
        assertEquals("public", Metric.AccessLevel.PUBLIC.asPropertyValue());
        assertEquals("admin", Metric.AccessLevel.ADMIN.asPropertyValue());
    }

    @Test
    void parsesAdminInAnyCase()
    {
        assertSame(Metric.AccessLevel.ADMIN, Metric.AccessLevel.fromPropertyValue("admin"));
        assertSame(Metric.AccessLevel.ADMIN, Metric.AccessLevel.fromPropertyValue("ADMIN"));
    }

    @Test
    void anythingElseIsPublic()
    {
        assertSame(Metric.AccessLevel.PUBLIC, Metric.AccessLevel.fromPropertyValue("public"));
        assertSame(Metric.AccessLevel.PUBLIC, Metric.AccessLevel.fromPropertyValue(null));
        assertSame(Metric.AccessLevel.PUBLIC, Metric.AccessLevel.fromPropertyValue("garbage"));
    }

    @Test
    void listsBothLevels()
    {
        assertEquals(2, Metric.AccessLevel.values().length);
        assertSame(Metric.AccessLevel.PUBLIC, Metric.AccessLevel.valueOf("PUBLIC"));
        assertSame(Metric.AccessLevel.ADMIN, Metric.AccessLevel.valueOf("ADMIN"));
    }
}
