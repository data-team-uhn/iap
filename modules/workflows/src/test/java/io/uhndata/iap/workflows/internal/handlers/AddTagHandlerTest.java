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
package io.uhndata.iap.workflows.internal.handlers;

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

import io.uhndata.iap.tags.testing.PropertyTagging;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.internal.EngineFixture;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.RecordedRole;

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

    private PropertyTagging tagging;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.tagging = PropertyTagging.builder()
            .inCategories("draft", "lifecycle")
            .inCategories("active", "lifecycle")
            .installIn(this.context);
        this.target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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

        this.handler.execute(context(Map.of("tag", "active")).build());

        assertEquals(Set.of("draft", "sensitive", "active"), WorkflowFixture.tags(this.target));
        assertEquals(List.of(), this.tagging.getReplacements());
    }

    @Test
    void namesTheChangeByTheTagForTheHistory() throws Exception
    {
        final RecordingTaskContext adding = context(Map.of("tag", "submitted")).build();

        this.handler.execute(adding);

        assertEquals(List.of(new RecordedRole(this.target.getPath(), "tagged as submitted")), adding.getRoles());
    }

    @Test
    void placesTheTagInPlaceOfOthersWhenReplacingExisting() throws Exception
    {
        tag(this.target, "draft", "sensitive");

        this.handler.execute(context(Map.of("tag", "active", "replaceExisting", true)).build());

        assertEquals(List.of("active"), this.tagging.getReplacements());
        assertEquals(Set.of("sensitive", "active"), WorkflowFixture.tags(this.target));
    }

    @Test
    void readsReplaceExistingWrittenAsText() throws Exception
    {
        this.handler.execute(context(Map.of("tag", "approved", "replaceExisting", "true")).build());

        assertEquals(List.of("approved"), this.tagging.getReplacements());
    }

    @Test
    void tagsWhatTheExecutionCreated() throws Exception
    {
        final Resource created = this.context.create().resource("/Workflows/created", TYPE, "wf/WorkflowDefinition");

        this.handler.execute(context(Map.of("tag", "draft")).havingCreated(created.getPath()).build());

        assertEquals(Set.of("draft"), WorkflowFixture.tags(created));
        assertEquals(Set.of(), WorkflowFixture.tags(this.target));
    }

    @Test
    void refusesActivitiesNotConfiguringATag()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of()).build()));
    }

    @Test
    void refusesABlankTagConfiguration()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of("tag", " ")).build()));
    }

    @Test
    void refusesATagThatCannotBePlaced()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of("tag", PropertyTagging.UNDEFINED)).build()));
    }

    private void tag(final Resource resource, final String... tags)
    {
        this.context.resourceResolver().getResource(resource.getPath())
            .adaptTo(ModifiableValueMap.class).put("tags", tags);
    }

    /**
     * Starts a task context for an {@code addTag} activity configured with the given properties.
     *
     * @param configuration the activity's configuration
     * @return a builder for the context, for the test to add to
     */
    private RecordingTaskContext.Builder context(final Map<String, Object> configuration)
    {
        final Map<String, Object> properties = new HashMap<>(configuration);
        properties.put(TYPE, Activity.RESOURCE_TYPE);
        properties.put("elementId", "tag");
        properties.put("handler", AddTagHandler.HANDLER_NAME);
        final Activity activity = this.context.create().resource(ACTIVITY, properties).adaptTo(Activity.class);
        return RecordingTaskContext.aimedAt(this.target).forEvent("tag", Map.of()).performing(activity)
            .actingFor(ACTOR);
    }
}
