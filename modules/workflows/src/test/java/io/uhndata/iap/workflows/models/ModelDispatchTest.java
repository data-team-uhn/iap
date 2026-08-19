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

import java.lang.reflect.Constructor;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.SUPER_TYPE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModelDispatch}, the check both workflow hierarchies make before believing what they
 * adapted.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ModelDispatchTest
{
    private static final String PATH = "/Workflows/timeOff/1.0/node";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void acceptsANodeOfTheTypeItsModelIsRegisteredFor()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1"));

        assertTrue(ModelDispatch.isConcrete(resource.adaptTo(FlowNode.class)));
    }

    @Test
    void rejectsANodeOfATypeNoModelIsRegisteredFor()
    {
        // wf:FlowNode is abstract and the resource type of no model, so this arrives as whichever implementation
        // sorts first without being one
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, FlowNode.RESOURCE_TYPE, "elementId", "task_1"));

        assertFalse(ModelDispatch.isConcrete(resource.adaptTo(FlowNode.class)));
    }

    @Test
    void acceptsANodeOfATypeDerivedFromOneAModelIsRegisteredFor()
    {
        // A deployment's own node type under a concrete one gets that type's model, which is the right one for it
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, "wf/ReviewActivity", SUPER_TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1"));

        assertTrue(ModelDispatch.isConcrete(resource.adaptTo(FlowNode.class)));
    }

    @Test
    void isNotInstantiatable()
        throws Exception
    {
        final Constructor<ModelDispatch> constructor = ModelDispatch.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
