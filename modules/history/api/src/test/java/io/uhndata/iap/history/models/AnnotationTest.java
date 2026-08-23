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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Annotation}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnnotationTest
{
    private static final String PATH = "/History/ab/cd/ef/action/subject/note";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Annotation.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Annotation.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Annotation.class));
    }

    @Test
    void exposesWhatWasSaidAndByWhom()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Annotation.RESOURCE_TYPE,
            "author", "reviewer1",
            "note", "The budget figure here was wrong; corrected in the next revision",
            "resolution", "superseded"));
        final Annotation annotation = resource.adaptTo(Annotation.class);

        assertEquals("reviewer1", annotation.getAuthor());
        assertEquals("The budget figure here was wrong; corrected in the next revision", annotation.getNote());
        assertEquals("superseded", annotation.getResolution());
    }

    @Test
    void isARemarkWhenItReachesNoVerdict()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Annotation.RESOURCE_TYPE,
            "author", "reviewer1",
            "note", "Worth reading alongside the ethics amendment"));
        assertNull(resource.adaptTo(Annotation.class).getResolution());
    }

    @Test
    void readsAsBlankRatherThanFailingWhenTheRecordSaysNothing()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Annotation.RESOURCE_TYPE);
        final Annotation annotation = resource.adaptTo(Annotation.class);

        assertEquals("", annotation.getAuthor());
        assertEquals("", annotation.getNote());
    }
}
