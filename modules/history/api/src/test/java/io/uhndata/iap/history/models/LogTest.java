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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Log}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LogTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Log.class, Action.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/History",
            "sling:resourceType", Log.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Log.class));
    }

    @Test
    void listsTheBucketsOfThePrefixTree()
    {
        final Resource root = this.context.create().resource("/History",
            "sling:resourceType", Log.RESOURCE_TYPE);
        this.context.create().resource("/History/ab", "sling:resourceType", Log.RESOURCE_TYPE);
        this.context.create().resource("/History/cd", "sling:resourceType", Log.RESOURCE_TYPE);

        final Log log = root.adaptTo(Log.class);
        assertEquals(2, log.getBuckets().size());
        assertTrue(log.getActions().isEmpty(), "Actions are at the bottom of the tree, not at the root");
    }

    @Test
    void listsTheActionsAtTheBottomOfTheTree()
    {
        final Resource bucket = this.context.create().resource("/History/ab/cd/ef",
            "sling:resourceType", Log.RESOURCE_TYPE);
        this.context.create().resource("/History/ab/cd/ef/action", Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "actor", "reviewer1",
            "operation", "submit"));

        final Log log = bucket.adaptTo(Log.class);
        assertEquals(1, log.getActions().size());
        assertTrue(log.getBuckets().isEmpty());
    }
}
