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
import java.util.List;
import java.util.Map;

import jakarta.servlet.Servlet;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.ServiceReference;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.models.WorkflowFixture;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WorkflowEventServletRegistrar}: the event servlet is bound to the resource types the system
 * workflows target, and follows them as they change.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowEventServletRegistrarTest
{
    private final SlingContext context = new SlingContext();

    private final WorkflowEventServletRegistrar registrar = new WorkflowEventServletRegistrar();

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        WorkflowFixture.setUp(this.context);
        inject("engine", Mockito.mock(WorkflowEngine.class));
    }

    @Test
    void bindsTheTypesTheSystemWorkflowsTarget() throws Exception
    {
        EngineFixture.createSystemWorkflow(this.context, true, true, "wf/WorkflowsHomepage");
        // An inactive version still keeps its type out of the Sling POST servlet's reach
        this.context.create().resource("/SystemWorkflows/editSchema", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Edit", "active", false));
        this.context.create().resource("/SystemWorkflows/editSchema/v1", Map.of(
            TYPE, "wf/WorkflowVersion", "version", "1.0", "active", false, "targetResourceType", "sch/Schema"));

        activate();

        assertArrayEquals(new String[] { "sch/Schema", "wf/TaskInstance", "wf/WorkflowsHomepage" }, boundTypes());
        assertEquals("POST", servlet().getProperty("sling.servlet.methods"));
    }

    @Test
    void followsTheSystemWorkflowsAsTheyChange() throws Exception
    {
        activate();
        assertArrayEquals(new String[] { "wf/TaskInstance" }, boundTypes());

        EngineFixture.createSystemWorkflow(this.context, true, true, "wf/WorkflowsHomepage");
        this.context.resourceResolver().commit();
        this.registrar.onChange(List.of());

        assertArrayEquals(new String[] { "wf/TaskInstance", "wf/WorkflowsHomepage" }, boundTypes());

        // Nothing that matters changed: the registration is left alone
        final Object before = servlet().getProperty("sling.servlet.resourceTypes");
        this.registrar.onChange(List.of());
        assertEquals(before, servlet().getProperty("sling.servlet.resourceTypes"));
    }

    @Test
    void bindsOnlyTasksWithoutItsServiceUser() throws Exception
    {
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        inject("resolverFactory", broken);
        this.registrar.activate(this.context.bundleContext());

        assertArrayEquals(new String[] { "wf/TaskInstance" }, boundTypes());
    }

    @Test
    void takesTheServletDownWithIt() throws Exception
    {
        activate();

        this.registrar.deactivate();

        assertNull(this.context.bundleContext().getServiceReference(Servlet.class));
    }

    private void activate() throws ReflectiveOperationException, Exception
    {
        this.context.resourceResolver().commit();
        inject("resolverFactory", EngineFixture.serviceUsers(this.context, null));
        this.registrar.activate(this.context.bundleContext());
    }

    private ServiceReference<Servlet> servlet()
    {
        return this.context.bundleContext().getServiceReference(Servlet.class);
    }

    private String[] boundTypes()
    {
        return (String[]) servlet().getProperty("sling.servlet.resourceTypes");
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field reference = WorkflowEventServletRegistrar.class.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(this.registrar, value);
    }
}
