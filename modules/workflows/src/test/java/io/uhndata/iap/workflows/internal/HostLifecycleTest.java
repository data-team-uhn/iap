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

import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.tags.internal.TagOperations;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.WorkflowFixture;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of what an end event does to the resource its instance was driving: the lifecycle state it leaves behind,
 * and which of the host's existing tags that displaces.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class HostLifecycleTest
{
    private static final String HOST = "/Submissions/aLongWeekend";

    private static final String ELEMENT_ID = "elementId";

    private static final String HOST_TAG = "hostTag";

    private static final String TAGS = "tags";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.registerService(TagOperations.class, EngineFixture.lifecycleTags());
    }

    @Test
    void recordsWhatFinishingMeantAsATagOnTheHost() throws Exception
    {
        final Resource host = host("draft");

        HostLifecycle.record(host, end("approved"));

        assertEquals(Set.of("approved"), EngineFixture.tagsOf(host));
    }

    @Test
    void retiresTheStateItReplaces() throws Exception
    {
        // The point of the categories: a submission that has just been approved has to stop being in review, or
        // every reader that asks what state it is in gets to pick its own answer
        final Resource host = host("in-review");

        HostLifecycle.record(host, end("rejected"));

        assertEquals(Set.of("rejected"), EngineFixture.tagsOf(host));
    }

    @Test
    void leavesMarkersFromOutsideTheLifecycleAlone() throws Exception
    {
        // A host may be under more than one process, and carries markers that have nothing to do with this one.
        // `urgent` is in no category this vocabulary knows, which is also the case of a tag whose definition has
        // since stopped applying, and both must survive.
        final Resource host = host("draft", "urgent");

        HostLifecycle.record(host, end("approved"));

        assertEquals(Set.of("approved", "urgent"), EngineFixture.tagsOf(host));
    }

    @Test
    void refusesAStateNothingHasDefined()
    {
        // A definition naming a tag that does not exist is broken, and saying so beats leaving the submission in
        // whatever state it happened to be in while the process reports success
        final Resource host = host("draft");

        assertThrows(WorkflowDefinitionException.class, () -> HostLifecycle.record(host, end("mislaid")));
        assertEquals(Set.of("draft"), EngineFixture.tagsOf(host));
    }

    private Resource host(final String... tags)
    {
        return this.context.create().resource(HOST, Map.of(TYPE, "sub:Submission", TAGS, tags));
    }

    private EndEvent end(final String hostTag)
    {
        final Resource resource = this.context.create().resource("/Workflows/w/v1/end_" + hostTag,
            Map.of(TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end_" + hostTag, HOST_TAG, hostTag));
        return resource.adaptTo(EndEvent.class);
    }
}
