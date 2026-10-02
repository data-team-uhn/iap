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
package io.uhndata.iap.history.models;

import java.util.Calendar;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LastSeen}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LastSeenTest
{
    private static final String PATH = "/Submissions/aStudy/" + LastSeen.NODE_NAME;

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, LastSeen.class, Marker.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", LastSeen.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(LastSeen.class));
    }

    @Test
    void listsEverybodyWhoHasLooked()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", LastSeen.RESOURCE_TYPE);
        this.marker("reviewer1");
        this.marker("submitter1");

        assertEquals(2, resource.adaptTo(LastSeen.class).getMarkers().size());
    }

    @Test
    void hasNoMarkersBeforeAnybodyLooks()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", LastSeen.RESOURCE_TYPE);
        assertTrue(resource.adaptTo(LastSeen.class).getMarkers().isEmpty());
    }

    @Test
    void findsOnePersonsMarkerByTheirUserId()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", LastSeen.RESOURCE_TYPE);
        this.marker("reviewer1");

        assertNotNull(resource.adaptTo(LastSeen.class).getMarker("reviewer1"));
    }

    @Test
    void hasNoMarkerForSomebodyWhoHasNeverLooked()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", LastSeen.RESOURCE_TYPE);
        this.marker("reviewer1");

        assertNull(resource.adaptTo(LastSeen.class).getMarker("reviewer2"));
    }

    private void marker(final String userId)
    {
        this.context.create().resource(PATH + "/" + userId, Map.of(
            "sling:resourceType", Marker.RESOURCE_TYPE,
            "seenAt", Calendar.getInstance()));
    }
}
