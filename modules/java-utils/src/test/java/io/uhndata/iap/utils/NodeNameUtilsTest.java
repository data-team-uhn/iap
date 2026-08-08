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
package io.uhndata.iap.utils;

import java.util.stream.IntStream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeNameUtils}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NodeNameUtilsTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void camelCasesTitles()
    {
        assertEquals("myCoolWorkflow", NodeNameUtils.camelCase("My cool workflow"));
        assertEquals("reviews", NodeNameUtils.camelCase("REVIEWS"));
        assertEquals("urgentReviews", NodeNameUtils.camelCase("  (Urgent) reviews!  "));
        assertEquals("évaluationDesCongés", NodeNameUtils.camelCase("Évaluation des congés"));
        assertEquals("day1Of3", NodeNameUtils.camelCase("Day 1 of 3"));
    }

    @Test
    void yieldsAnEmptyNameWhenNothingUsableRemains()
    {
        assertTrue(NodeNameUtils.camelCase("!!! ???").isEmpty());
        assertTrue(NodeNameUtils.camelCase("").isEmpty());
    }

    @Test
    void findsTheNaturalNameWhenItIsFree()
    {
        final Resource parent = this.context.create().resource("/content");

        assertEquals("report", NodeNameUtils.findFreeName(parent, "report"));
    }

    @Test
    void countsPastTakenNames()
    {
        final Resource parent = this.context.create().resource("/content");
        this.context.create().resource("/content/report");
        this.context.create().resource("/content/report2");

        assertEquals("report3", NodeNameUtils.findFreeName(parent, "report"));
    }

    @Test
    void givesUpWhenEveryVariantIsTaken()
    {
        final Resource parent = this.context.create().resource("/content");
        IntStream.rangeClosed(1, 100).forEach(attempt -> this.context.create().resource(
            "/content/" + (attempt == 1 ? "busy" : "busy" + attempt)));

        assertNull(NodeNameUtils.findFreeName(parent, "busy"));
    }
}
