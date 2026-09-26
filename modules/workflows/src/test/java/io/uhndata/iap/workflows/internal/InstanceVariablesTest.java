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

import java.lang.reflect.Constructor;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowToken;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InstanceVariables}: what a handler recorded becomes a child the gateways can read, what
 * an earlier walk recorded comes back to the next one, and nothing else under the instance is touched.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class InstanceVariablesTest
{
    private static final String INSTANCE = "/Submissions/aLongWeekend/wf:instances/readProposal";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage");
        this.context.create().resource("/Submissions/aLongWeekend", TYPE, "sub/Submission");
        this.context.create().resource("/Submissions/aLongWeekend/wf:instances", TYPE, "wf/WorkflowInstances");
        this.context.create().resource(INSTANCE, TYPE, WorkflowInstance.RESOURCE_TYPE);
        this.resolver = EngineFixture.serviceUsers(this.context, null).getServiceResourceResolver(Map.of());
    }

    @Test
    void writesEachKnownTypeAndTakesANullBackDown() throws Exception
    {
        final Calendar when = Calendar.getInstance();
        final Map<String, Object> values = new HashMap<>();
        values.put("verdict", "PROPOSAL");
        values.put("flag", Boolean.TRUE);
        values.put("confidence", 0.75);
        values.put("approx", 1.5f);
        values.put("count", 3);
        values.put("when", when);
        values.put("skip", new Object());
        values.put(" ", "ignored");
        values.put(null, "ignored");
        InstanceVariables.flush(instance(), values);

        assertEquals("PROPOSAL", value("verdict"));
        assertEquals(Boolean.TRUE, value("flag"));
        assertEquals(0.75, value("confidence"));
        assertEquals(1.5, value("approx"));
        assertEquals(3L, value("count"));
        assertEquals(when.getTimeInMillis(), ((Calendar) value("when")).getTimeInMillis());
        assertNull(instance().getChild("skip"));
        assertNull(instance().getChild(" "));

        InstanceVariables.persist(instance(), "verdict", null);
        assertNull(instance().getChild("verdict"));
        InstanceVariables.persist(instance(), "gone", null);
    }

    @Test
    void replacesAnEarlierValueAndClearsTheOldType() throws Exception
    {
        InstanceVariables.persist(instance(), "picked", "first");
        InstanceVariables.persist(instance(), "picked", 2);

        assertEquals(2L, value("picked"));
        assertNull(instance().getChild("picked").getValueMap().get("stringValue"));
    }

    @Test
    void leavesATokenAloneWhenAHandlerReusesItsName() throws Exception
    {
        this.resolver.create(instance(), "busy", Map.of(
            "jcr:primaryType", "wf:WorkflowToken", "currentNodeId", "start"));

        InstanceVariables.persist(instance(), "busy", "PROPOSAL");
        InstanceVariables.persist(instance(), "busy", null);

        assertTrue(instance().getChild("busy").isResourceType(WorkflowToken.RESOURCE_TYPE));
        assertNull(instance().getChild("busy").getValueMap().get("stringValue"));
    }

    @Test
    void readsBackWhatAnEarlierWalkRecorded() throws Exception
    {
        InstanceVariables.persist(instance(), "verdict", "PROPOSAL");
        InstanceVariables.persist(instance(), "confidence", 0.75);
        this.resolver.create(instance(), "busy", Map.of(
            "jcr:primaryType", "wf:WorkflowToken", "currentNodeId", "start"));

        final Map<String, Object> values = new HashMap<>();
        // What this walk has already said stands, and a name it cleared stays cleared
        values.put("verdict", "NOT_PROPOSAL");
        values.put("confidence", null);
        InstanceVariables.load(instance(), values);

        assertEquals("NOT_PROPOSAL", values.get("verdict"));
        assertNull(values.get("confidence"));
        assertTrue(values.containsKey("confidence"));
        assertFalse(values.containsKey("busy"));
    }

    @Test
    void writesOnlyWhatTheInstanceDoesNotSayAlready() throws Exception
    {
        InstanceVariables.persist(instance(), "verdict", "PROPOSAL");
        final Calendar written = instance().getChild("verdict").getValueMap().get("jcr:created", Calendar.class);

        final Map<String, Object> values = new HashMap<>();
        values.put("verdict", "PROPOSAL");
        values.put("category", "/Categories/Trials");
        InstanceVariables.flush(instance(), values);

        // The one that was already there is left as it was, the new one is written
        assertEquals(written, instance().getChild("verdict").getValueMap().get("jcr:created", Calendar.class));
        assertEquals("/Categories/Trials", value("category"));
    }

    @Test
    void existsOnlyForItsHelpers() throws Exception
    {
        final Constructor<InstanceVariables> constructor = InstanceVariables.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    private Resource instance()
    {
        return this.resolver.getResource(INSTANCE);
    }

    private Object value(final String name)
    {
        return instance().adaptTo(WorkflowInstance.class).getVariable(name).getValue();
    }
}
