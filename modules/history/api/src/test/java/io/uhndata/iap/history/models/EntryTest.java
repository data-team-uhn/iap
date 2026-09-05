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

import java.util.List;
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
 * Unit tests for {@link Entry}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntryTest
{
    private static final String ACTION_PATH = "/History/ab/cd/ef/action";

    private static final String PATH = ACTION_PATH + "/subject";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Action.class, Entry.class, Annotation.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Entry.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Entry.class));
    }

    @Test
    void exposesWhatWasDoneToWhich()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Entry.RESOURCE_TYPE,
            "subject", "aaaaaaaa-0000-0000-0000-000000000001",
            "subjectPath", "/Submissions/aStudy",
            "subjectType", "sub:Submission",
            "role", "submitted",
            "changes", new String[] {"status", "title"},
            "snapshot", "55555555-5555-5555-5555-555555555555"));
        final Entry entry = resource.adaptTo(Entry.class);

        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", entry.getSubject());
        assertEquals("/Submissions/aStudy", entry.getSubjectPath());
        assertEquals("sub:Submission", entry.getSubjectType());
        assertEquals("submitted", entry.getRole());
        assertEquals(List.of("status", "title"), entry.getChanges());
        assertEquals("55555555-5555-5555-5555-555555555555", entry.getSnapshot());
    }

    @Test
    void readsAsBlankRatherThanFailingWhenTheRecordSaysNothing()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Entry.RESOURCE_TYPE);
        final Entry entry = resource.adaptTo(Entry.class);

        assertEquals("", entry.getSubject());
        assertEquals("", entry.getSubjectPath());
        assertEquals("", entry.getSubjectType());
        assertEquals("", entry.getRole());
        assertTrue(entry.getChanges().isEmpty());
        assertNull(entry.getSnapshot(), "No snapshot is the normal case, not a gap");
    }

    @Test
    void findsTheActionItIsPartOf()
    {
        this.context.create().resource(ACTION_PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "actor", "reviewer1",
            "operation", "submit"));
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Entry.RESOURCE_TYPE);

        final Action action = resource.adaptTo(Entry.class).getAction();
        assertNotNull(action);
        assertEquals("reviewer1", action.getActor());
    }

    @Test
    void hasNoActionWhenItIsNotFiledUnderOne()
    {
        this.context.create().resource("/History/ab/cd/ef", "sling:resourceType", Log.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource("/History/ab/cd/ef/stray",
            "sling:resourceType", Entry.RESOURCE_TYPE);
        assertNull(resource.adaptTo(Entry.class).getAction());
    }

    @Test
    void listsWhatWasSaidAboutItAfterwards()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Entry.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/note", Map.of(
            "sling:resourceType", Annotation.RESOURCE_TYPE,
            "author", "reviewer1",
            "note", "This is the revision the approval was granted on"));

        assertEquals(1, resource.adaptTo(Entry.class).getAnnotations().size());
    }

    @Test
    void hasNoAnnotationsUntilSomebodyAddsOne()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Entry.RESOURCE_TYPE);
        assertTrue(resource.adaptTo(Entry.class).getAnnotations().isEmpty());
    }
}
