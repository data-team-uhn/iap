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
package io.uhndata.iap.workflows.models;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowDefinition}, including the properties it inherits from {@link Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowDefinitionTest
{
    private static final String PATH = "/Workflows/timeOff";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void exposesDefinitionProperties()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE,
            "title", "Time off request"));
        final WorkflowDefinition definition = resource.adaptTo(WorkflowDefinition.class);

        assertNotNull(definition);
        assertEquals("Time off request", definition.getTitle());
    }

    @Test
    void runsWhileOneOfItsVersionsIsActive()
    {
        // Not a flag of the definition's own: a workflow runs through a version or not at all, so this is the same
        // question as whether it has an active version, asked of the definition
        assertTrue(this.createDefinitionWithVersions("RETIRED", "ACTIVE", "DRAFT")
            .adaptTo(WorkflowDefinition.class).isActive());
    }

    @Test
    void doesNotRunWhileNoVersionOfItIsActive()
    {
        // A version on trial is not the one instances are created from, so a workflow whose only versions are a
        // draft and a trial still runs nothing
        assertFalse(this.createDefinitionWithVersions("DRAFT", "TRIAL", "RETIRED")
            .adaptTo(WorkflowDefinition.class).isActive());
    }

    @Test
    void defaultsToInactive()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, WorkflowDefinition.RESOURCE_TYPE);
        final WorkflowDefinition definition = resource.adaptTo(WorkflowDefinition.class);

        assertNotNull(definition);
        assertNull(definition.getTitle());
        assertFalse(definition.isActive());
        assertTrue(definition.getVersions().isEmpty());
    }

    @Test
    void listsItsVersions()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(PATH + "/1.0", Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0"));
        this.context.create().resource(PATH + "/2.0", Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "2.0"));

        final List<WorkflowVersion> versions = resource.adaptTo(WorkflowDefinition.class).getVersions();

        assertEquals(2, versions.size());
        assertEquals("1.0", versions.get(0).getVersion());
        assertEquals("2.0", versions.get(1).getVersion());
    }

    @Test
    void namesTheActiveVersionAmongItsVersions()
    {
        final Resource resource = this.createDefinitionWithVersions("RETIRED", "ACTIVE", "DRAFT");

        final WorkflowVersion active = resource.adaptTo(WorkflowDefinition.class).getActiveVersion();

        assertNotNull(active);
        assertEquals("2.0", active.getVersion());
    }

    @Test
    void hasNoActiveVersionWhileEveryVersionIsADraft()
    {
        // Between a workflow's first draft and its promotion there is nothing to run, and the same is true again
        // once an active version is retired without a replacement
        final Resource resource = this.createDefinitionWithVersions("DRAFT", "DRAFT", "RETIRED");

        assertNull(resource.adaptTo(WorkflowDefinition.class).getActiveVersion());
    }

    @Test
    void inheritsEntityAndContentProperties()
    {
        final Calendar created = Calendar.getInstance();
        created.set(2026, Calendar.JULY, 31, 9, 0, 0);
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE,
            "jcr:uuid", "1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809",
            "jcr:created", created,
            "jcr:createdBy", "alice"));
        final WorkflowDefinition definition = resource.adaptTo(WorkflowDefinition.class);

        assertEquals(PATH, definition.getPath());
        assertEquals("timeOff", definition.getName());
        assertEquals(WorkflowDefinition.RESOURCE_TYPE, definition.getType());
        assertEquals("1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809", definition.getIdentifier());
        assertEquals(created, definition.getCreated());
        assertEquals("alice", definition.getCreatedBy());
    }

    @Test
    void adaptsToParentModels()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "jcr:uuid", "1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809"));

        final Entity entity = resource.adaptTo(Entity.class);
        assertNotNull(entity);
        assertEquals("1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809", entity.getIdentifier());

        final Content content = resource.adaptTo(Content.class);
        assertNotNull(content);
        assertEquals(WorkflowDefinition.RESOURCE_TYPE, content.getType());
    }

    /**
     * Creates a definition holding one version per given state, labelled {@code 1.0}, {@code 2.0}, and so on.
     *
     * @param states the lifecycle state of each version to create, in order
     * @return the definition's resource
     */
    private Resource createDefinitionWithVersions(final String... states)
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Time off request"));
        for (int i = 0; i < states.length; ++i) {
            final String label = (i + 1) + ".0";
            this.context.create().resource(PATH + "/" + label, Map.of(
                TYPE, WorkflowVersion.RESOURCE_TYPE, "version", label, "state", states[i]));
        }
        return resource;
    }
}
