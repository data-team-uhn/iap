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
package io.uhndata.iap.healthcheck.internal;

import java.util.Map;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that the OSGi services IAP needs are registered. Each child of {@code /libs/iap/healthcheck/requiredServices}
 * names one service. Its {@code serviceClass} holds the fully qualified name the service is registered under, and its
 * optional {@code osgiFilter} holds an OSGi filter the service must also match. A module adds the services it needs
 * as children of that node, in its own initial content.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HealthCheck.class, property = { HealthCheck.TAGS + "=iap",
    HealthCheck.NAME + "=IAP services active" }, immediate = true)
public class ServicesPresentHealthCheck implements HealthCheck
{
    /** The node whose children name the required services. */
    public static final String CONFIGURATION_PATH = "/libs/iap/healthcheck/requiredServices";

    /**
     * The property holding the fully qualified name a required service is registered under, such as
     * {@code org.apache.sling.api.resource.ResourceResolverFactory}.
     */
    public static final String SERVICE_PROPERTY = "serviceClass";

    /** The optional property holding an OSGi filter a required service must also match. */
    public static final String FILTER_PROPERTY = "osgiFilter";

    private static final Logger LOGGER = LoggerFactory.getLogger(ServicesPresentHealthCheck.class);

    private BundleContext context;

    @Reference
    private ResourceResolverFactory rrf;

    @Activate
    protected void activate(final BundleContext bundleContext)
    {
        this.context = bundleContext;
    }

    @Override
    public Result execute()
    {
        final FormattingResultLog result = new FormattingResultLog();
        int present = 0;
        int missing = 0;
        try (ResourceResolver resolver =
            this.rrf.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "healthcheck"))) {
            final Resource requiredServices = resolver.getResource(CONFIGURATION_PATH);
            if (requiredServices == null || requiredServices.isResourceType(Resource.RESOURCE_TYPE_NON_EXISTING)) {
                result.warn("No required services configuration, please check the system integrity");
                return new Result(result);
            }
            for (final Resource requiredService : requiredServices.getChildren()) {
                try {
                    if (checkService(requiredService, result)) {
                        present++;
                    } else {
                        missing++;
                    }
                } catch (final InvalidSyntaxException e) {
                    LOGGER.error("Invalid syntax: {}", e.getMessage(), e);
                    result.healthCheckError("Cannot run status check: {}", e.getMessage(), e);
                }
            }
        } catch (final LoginException e) {
            result.healthCheckError("Healthcheck module not set up properly: {}", e.getMessage());
        }
        result.info("{} required services active" + (missing != 0 ? " and {} missing" : ""), present, missing);
        return new Result(result);
    }

    /**
     * Checks one required service, and adds to the result whether it is registered.
     *
     * @param requiredService a child of {@link #CONFIGURATION_PATH}
     * @param result the log this check's outcome is added to
     * @return {@code true} if a service with that name, matching the filter if there is one, is registered;
     *         {@code false} if none is, or if the configuration names no service
     * @throws InvalidSyntaxException if the configured filter is not a valid OSGi filter
     */
    private boolean checkService(final Resource requiredService, final FormattingResultLog result)
        throws InvalidSyntaxException
    {
        final String serviceClass = requiredService.getValueMap().get(SERVICE_PROPERTY, "");
        final String osgiFilter = requiredService.getValueMap().get(FILTER_PROPERTY, "");
        if (serviceClass.isBlank()) {
            // Unlike a blank filter, a blank class name cannot become null: a null class name matches every service
            result.healthCheckError("Missing service class in configuration '{}'", requiredService.getName());
            return false;
        }
        final ServiceReference<?>[] implementations =
            this.context.getAllServiceReferences(serviceClass, osgiFilter.isBlank() ? null : osgiFilter);

        if (implementations == null || implementations.length == 0) {
            result.critical("Required service implementation not found: {}/{}", serviceClass, osgiFilter);
            return false;
        }
        result.debug("Found service: {}/{}", serviceClass, osgiFilter);
        return true;
    }
}
