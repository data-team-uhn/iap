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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubmissionsHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionsHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityHomepage.class, Submission.class,
            SubmissionsHomepage.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions",
            "sling:resourceType", SubmissionsHomepage.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(SubmissionsHomepage.class));
    }

    @Test
    void listsSubmissions()
    {
        final Resource resource = this.context.create().resource("/Submissions",
            "sling:resourceType", SubmissionsHomepage.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/first", "sling:resourceType", Submission.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/second", "sling:resourceType", Submission.RESOURCE_TYPE);
        final SubmissionsHomepage homepage = resource.adaptTo(SubmissionsHomepage.class);

        final List<Submission> submissions = homepage.getSubmissions();

        assertEquals(2, submissions.size());
        assertEquals("first", submissions.get(0).getName());
        assertEquals("second", submissions.get(1).getName());
    }

    @Test
    void listsNoSubmissionsWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Submissions",
            "sling:resourceType", SubmissionsHomepage.RESOURCE_TYPE);
        final SubmissionsHomepage homepage = resource.adaptTo(SubmissionsHomepage.class);

        assertTrue(homepage.getSubmissions().isEmpty());
    }
}
