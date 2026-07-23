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
 * Check that all the required services are present. The list of services to check is defined as nodes in the
 * repository, under {@code /libs/iap/healthcheck/requiredServices/}, with the {@code serviceClass} property holding
 * the fully qualified name of a service, and an optional {@code osgiFilter} property holding a valid OSGi service
 * filter. Other modules should provide the actual required services to check for.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HealthCheck.class, property = { HealthCheck.TAGS + "=iap",
    HealthCheck.NAME + "=IAP services active" }, immediate = true)
public class ServicesPresentHealthCheck implements HealthCheck
{
    /** JCR node where all the configurations are stored. */
    public static final String CONFIGURATION_PATH = "/libs/iap/healthcheck/requiredServices";

    /** The name of the configuration node property holding the fully qualified name of the service to check. */
    public static final String SERVICE_PROPERTY = "serviceClass";

    /** The name of the configuration node property holding an optional OSGi service filter to check with. */
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
     * Runs one configured check: at least one service implementation matching the configured class and optional
     * filter must be registered.
     *
     * @param requiredService the configuration resource describing the check
     * @param result the result log to report into
     * @return {@code true} if the service is present
     * @throws InvalidSyntaxException if the configured OSGi filter is invalid
     */
    private boolean checkService(final Resource requiredService, final FormattingResultLog result)
        throws InvalidSyntaxException
    {
        final String serviceClass = requiredService.getValueMap().get(SERVICE_PROPERTY, "");
        final String osgiFilter = requiredService.getValueMap().get(FILTER_PROPERTY, "");
        if (serviceClass.isBlank()) {
            // Passing an empty class name to getAllServiceReferences would match every registered service,
            // silently passing the check, so treat a blank configuration as an error instead
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
