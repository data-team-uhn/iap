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
package io.uhndata.iap.workflows.internal;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AddTagHandler}: placing a tag on the host, optionally replacing the host's tags that
 * share a category with it, and refusing what cannot be placed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AddTagHandlerTest
{
    private static final String ACTOR = "admin";

    private static final String ACTIVITY = EngineFixture.VERSION + "/tag";

    private final SlingContext context = new SlingContext();

    private final AddTagHandler handler = new AddTagHandler();

    private Resource target;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        WorkflowFixture.setUp(this.context);
        TaggingFixture.enable(this.context);
        this.target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        final Field tagManager = AddTagHandler.class.getDeclaredField("tagManager");
        tagManager.setAccessible(true);
        tagManager.set(this.handler, TaggingFixture.definitions(Map.of(
            "draft", List.of("lifecycle"),
            "active", List.of("lifecycle"),
            "approved", List.of("lifecycle", "review"),
            "in-progress", List.of("review"),
            "sensitive", List.of("privacy"))));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(AddTagHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void addsTheTagBesideTheOthers() throws Exception
    {
        tag(this.target, "draft", "sensitive");

        this.handler.execute(context(Map.of("tag", "active"), new HashMap<>()));

        assertEquals(Set.of("draft", "sensitive", "active"), TaggingFixture.tags(this.target));
    }

    @Test
    void replacesTheTagsSharingACategory() throws Exception
    {
        tag(this.target, "draft", "sensitive", "leftover");

        this.handler.execute(context(Map.of("tag", "active", "replaceExisting", true), new HashMap<>()));

        assertEquals(Set.of("sensitive", "leftover", "active"), TaggingFixture.tags(this.target));
    }

    @Test
    void replacesTheTagsSharingAnyOfItsCategories() throws Exception
    {
        tag(this.target, "draft", "in-progress", "sensitive");

        this.handler.execute(context(Map.of("tag", "approved", "replaceExisting", "true"), new HashMap<>()));

        assertEquals(Set.of("sensitive", "approved"), TaggingFixture.tags(this.target));
    }

    @Test
    void keepsTheTagWhenItIsAlreadyThere() throws Exception
    {
        tag(this.target, "active");

        this.handler.execute(context(Map.of("tag", "active", "replaceExisting", true), new HashMap<>()));

        assertEquals(Set.of("active"), TaggingFixture.tags(this.target));
    }

    @Test
    void tagsWhatTheExecutionCreated() throws Exception
    {
        final Resource created = this.context.create().resource("/Workflows/created", TYPE, "wf/WorkflowDefinition");
        final Map<String, Object> variables = new HashMap<>();
        variables.put(WorkflowResult.CREATED_PATH_VARIABLE, created.getPath());

        this.handler.execute(context(Map.of("tag", "draft"), variables));

        assertEquals(Set.of("draft"), TaggingFixture.tags(created));
        assertEquals(Set.of(), TaggingFixture.tags(this.target));
    }

    @Test
    void refusesActivitiesNotConfiguringATag()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of(), new HashMap<>())));
    }

    @Test
    void refusesABlankTagConfiguration()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of("tag", " "), new HashMap<>())));
    }

    @Test
    void refusesATagThatCannotBePlaced()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of("tag", TaggingFixture.UNDEFINED), new HashMap<>())));
    }

    private void tag(final Resource resource, final String... tags)
    {
        this.context.resourceResolver().getResource(resource.getPath())
            .adaptTo(ModifiableValueMap.class).put("tags", tags);
    }

    /**
     * Builds a task context for an {@code addTag} activity configured with the given properties.
     *
     * @param configuration the activity's configuration
     * @param variables the execution's variables
     * @return the assembled context
     */
    private WorkflowTaskContextImpl context(final Map<String, Object> configuration,
        final Map<String, Object> variables)
    {
        final Map<String, Object> properties = new HashMap<>(configuration);
        properties.put(TYPE, Activity.RESOURCE_TYPE);
        properties.put("elementId", "tag");
        properties.put("handler", AddTagHandler.HANDLER_NAME);
        final Activity activity = this.context.create().resource(ACTIVITY, properties).adaptTo(Activity.class);
        return new WorkflowTaskContextImpl(this.target, new WorkflowEvent("tag", Map.of()), activity, variables,
            ACTOR, EngineFixture.noFurtherTasks(), 0);
    }
}
