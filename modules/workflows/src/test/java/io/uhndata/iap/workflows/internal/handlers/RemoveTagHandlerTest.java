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
import io.uhndata.iap.workflows.internal.EngineFixture;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.RecordedRole;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RemoveTagHandler}: removing a tag from the host, and nothing else.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RemoveTagHandlerTest
{
    private static final String ACTOR = "admin";

    private static final String ACTIVITY = EngineFixture.VERSION + "/tag";

    private final SlingContext context = new SlingContext();

    private final RemoveTagHandler handler = new RemoveTagHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        PropertyTagging.installIn(this.context);
        this.target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RemoveTagHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void removesOnlyTheTag() throws Exception
    {
        tag(this.target, "retired", "sensitive");

        this.handler.execute(context(Map.of("tag", "retired")).build());

        assertEquals(Set.of("sensitive"), WorkflowFixture.tags(this.target));
    }

    @Test
    void doesNothingWhenTheTagIsNotThere() throws Exception
    {
        tag(this.target, "sensitive");

        this.handler.execute(context(Map.of("tag", "retired")).build());

        assertEquals(Set.of("sensitive"), WorkflowFixture.tags(this.target));
    }

    private void tag(final Resource resource, final String... tags)
    {
        this.context.resourceResolver().getResource(resource.getPath())
            .adaptTo(ModifiableValueMap.class).put("tags", tags);
    }

    @Test
    void namesTheChangeByTheTagForTheHistory() throws Exception
    {
        final RecordingTaskContext untagging = context(Map.of("tag", "draft")).build();

        this.handler.execute(untagging);

        assertEquals(List.of(new RecordedRole(this.target.getPath(), "untagged as draft")), untagging.getRoles());
    }

    /**
     * Starts a task context for an {@code removeTag} activity configured with the given properties.
     *
     * @param configuration the activity's configuration
     * @return a builder for the context, for the test to add to
     */
    private RecordingTaskContext.Builder context(final Map<String, Object> configuration)
    {
        final Map<String, Object> properties = new HashMap<>(configuration);
        properties.put(TYPE, Activity.RESOURCE_TYPE);
        properties.put("elementId", "tag");
        properties.put("handler", RemoveTagHandler.HANDLER_NAME);
        final Activity activity = this.context.create().resource(ACTIVITY, properties).adaptTo(Activity.class);
        return RecordingTaskContext.aimedAt(this.target).forEvent("tag", Map.of()).performing(activity)
            .actingFor(ACTOR);
    }
}
