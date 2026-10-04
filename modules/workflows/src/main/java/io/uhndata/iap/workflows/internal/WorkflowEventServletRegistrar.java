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

import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.Servlet;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.api.servlets.HttpConstants;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Brings under workflow control the resource types the system workflows say they handle: every POST to a resource
 * of one of them, or to a user task, goes to the {@link WorkflowEventServlet}. Which types those are is content, the
 * {@code targetResourceType} of each system workflow version, so the servlet is registered here rather than by
 * annotation, and registered again whenever {@code /SystemWorkflows} changes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, property = {
    ResourceChangeListener.PATHS + "=" + SystemWorkflowsHomepage.PATH,
    ResourceChangeListener.CHANGES + "=ADDED",
    ResourceChangeListener.CHANGES + "=CHANGED",
    ResourceChangeListener.CHANGES + "=REMOVED"
})
public class WorkflowEventServletRegistrar implements ResourceChangeListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEventServletRegistrar.class);

    /** The subservice name under which the engine's service user is mapped. */
    private static final String SUBSERVICE_NAME = "workflows";

    @Reference
    private WorkflowEngine engine;

    @Reference
    private ResourceResolverFactory resolverFactory;

    private ServiceRegistration<Servlet> registration;

    private Set<String> types;

    /**
     * Registers the servlet for the types the system workflows target now.
     *
     * @param context the bundle context to register the servlet through
     */
    @Activate
    public synchronized void activate(final BundleContext context)
    {
        this.types = controlledTypes();
        this.registration = register(context, this.types);
    }

    /** Takes the servlet down with the component. */
    @Deactivate
    public synchronized void deactivate()
    {
        this.registration.unregister();
    }

    @Override
    public synchronized void onChange(final List<ResourceChange> changes)
    {
        final Set<String> current = controlledTypes();
        if (!current.equals(this.types)) {
            // Registered anew rather than updated: the servlet resolver binds the types a servlet has when it is
            // registered and ignores later changes to its properties. The new registration goes up before the old
            // one comes down, so no event falls through to the Sling POST servlet in between
            final ServiceRegistration<Servlet> previous = this.registration;
            this.types = current;
            this.registration = register(previous.getReference().getBundle().getBundleContext(), current);
            previous.unregister();
        }
    }

    /**
     * Registers a servlet for the given types.
     *
     * @param context the bundle context to register it through
     * @param resourceTypes the types it binds
     * @return the registration
     */
    private ServiceRegistration<Servlet> register(final BundleContext context, final Set<String> resourceTypes)
    {
        return context.registerService(Servlet.class, new WorkflowEventServlet(this.engine), properties(resourceTypes));
    }

    /**
     * The resource types under workflow control: user tasks, and whatever a system workflow version targets. A
     * version that is not active still counts, so that an event aimed at its type is refused by the engine rather
     * than falling through to the Sling POST servlet.
     *
     * @return the types, sorted
     */
    private Set<String> controlledTypes()
    {
        final Set<String> found = new TreeSet<>();
        found.add(TaskInstance.RESOURCE_TYPE);
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Resource home = resolver.getResource(SystemWorkflowsHomepage.PATH);
            final SystemWorkflowsHomepage homepage =
                home == null ? null : home.adaptTo(SystemWorkflowsHomepage.class);
            if (homepage != null) {
                homepage.getWorkflows().stream()
                    .flatMap(definition -> definition.getVersions().stream())
                    .map(WorkflowVersion::getTargetResourceType)
                    .filter(Objects::nonNull)
                    .forEach(found::add);
            }
        } catch (final LoginException e) {
            LOGGER.error("The workflow engine's service user is not available, so only user tasks take events: {}",
                e.getMessage(), e);
        }
        return found;
    }

    /**
     * The servlet's registration properties.
     *
     * @param resourceTypes the types it binds
     * @return the properties
     */
    private static Dictionary<String, Object> properties(final Set<String> resourceTypes)
    {
        return FrameworkUtil.asDictionary(Map.of(
            "sling.servlet.resourceTypes", resourceTypes.toArray(String[]::new),
            "sling.servlet.methods", HttpConstants.METHOD_POST));
    }
}
