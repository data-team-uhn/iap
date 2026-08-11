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
import io.uhndata.iap.utils.PrefixTree;

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
    void listsSubmissionsFiledInThePrefixTree()
    {
        // Where submissions actually live: spread over buckets named after the first characters of their names,
        // so they are descendants of the homepage rather than children of it
        final Resource resource = this.context.create().resource("/Submissions",
            "sling:resourceType", SubmissionsHomepage.RESOURCE_TYPE);
        final String name = "0a1b2c3d-0000-0000-0000-000000000000";
        this.context.create().resource(PrefixTree.pathFor("/Submissions", name),
            "sling:resourceType", Submission.RESOURCE_TYPE);
        // A bucket holding nothing must not be mistaken for a submission, and must not stop the walk either
        this.context.create().resource("/Submissions/ff/ff/ff", "jcr:primaryType", "sling:Folder");
        final SubmissionsHomepage homepage = resource.adaptTo(SubmissionsHomepage.class);

        final List<Submission> submissions = homepage.getSubmissions();

        assertEquals(1, submissions.size());
        assertEquals(name, submissions.get(0).getName());
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
