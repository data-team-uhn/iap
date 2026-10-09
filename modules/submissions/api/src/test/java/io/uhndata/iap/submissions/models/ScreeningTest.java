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
package io.uhndata.iap.submissions.models;

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Screening}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ScreeningTest
{
    private static final String PATH = "/Submissions/submission/screening";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Screening.class);
    }

    @Test
    void exposesItsSummary()
    {
        final Screening screening = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Screening.RESOURCE_TYPE, "summary", "Mostly complete")).adaptTo(Screening.class);

        assertEquals("Mostly complete", screening.getSummary());
    }

    @Test
    void toleratesAMissingSummary()
    {
        final Screening screening = this.context.create().resource(PATH, "sling:resourceType",
            Screening.RESOURCE_TYPE).adaptTo(Screening.class);

        assertNull(screening.getSummary());
    }
}
