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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Marker}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkerTest
{
    private static final String PATH = "/Submissions/aStudy/" + LastSeen.NODE_NAME + "/reviewer1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Marker.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Marker.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Marker.class));
    }

    @Test
    void exposesHowFarTheyGot()
    {
        final Calendar seen = Calendar.getInstance();
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Marker.RESOURCE_TYPE,
            "seenAt", seen,
            "seenAction", "11111111-1111-1111-1111-111111111111",
            "seenSnapshot", "22222222-2222-2222-2222-222222222222"));
        final Marker marker = resource.adaptTo(Marker.class);

        assertEquals(seen, marker.getSeenAt());
        assertEquals("11111111-1111-1111-1111-111111111111", marker.getSeenAction());
        assertEquals("22222222-2222-2222-2222-222222222222", marker.getSeenSnapshot());
    }

    @Test
    void saysNothingItWasNotTold()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Marker.RESOURCE_TYPE);
        final Marker marker = resource.adaptTo(Marker.class);

        assertNull(marker.getSeenAt());
        assertNull(marker.getSeenAction());
        assertNull(marker.getSeenSnapshot());
    }

    @Test
    void isBehindWhenSomethingHappenedSinceTheyLooked()
    {
        final Calendar looked = Calendar.getInstance();
        looked.add(Calendar.HOUR, -2);
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Marker.RESOURCE_TYPE,
            "seenAt", looked));

        assertTrue(resource.adaptTo(Marker.class).isBehind(Calendar.getInstance()));
    }

    @Test
    void isUpToDateWhenNothingHasHappenedSince()
    {
        final Calendar happened = Calendar.getInstance();
        happened.add(Calendar.HOUR, -2);
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Marker.RESOURCE_TYPE,
            "seenAt", Calendar.getInstance()));

        assertFalse(resource.adaptTo(Marker.class).isBehind(happened));
    }

    @Test
    void isBehindWhenTheMarkerCannotSayWhenTheyLooked()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Marker.RESOURCE_TYPE);
        assertTrue(resource.adaptTo(Marker.class).isBehind(Calendar.getInstance()),
            "An unreadable marker must not claim somebody is up to date");
    }
}
