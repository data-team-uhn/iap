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

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that the repository properties IAP needs exist. Each child of
 * {@code /libs/iap/healthcheck/requiredProperties} names one property. Its {@code propertyPath} holds the property's
 * absolute path, and its optional {@code requiredValue} holds the value the property must have. The values are
 * compared as strings, so a {@code requiredValue} of {@code true} matches a boolean property. Without a
 * {@code requiredValue}, the property only has to exist. A module adds the properties it needs as children of that
 * node, in its own initial content.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HealthCheck.class, property = { HealthCheck.TAGS + "=iap",
    HealthCheck.NAME + "=IAP properties present" }, immediate = true)
public class PropertiesPresentHealthCheck implements HealthCheck
{
    /** The node whose children name the required properties. */
    public static final String CONFIGURATION_PATH = "/libs/iap/healthcheck/requiredProperties";

    /**
     * The property holding the absolute path of a required property, such as {@code /libs/iap/conf/Media/logoLight}.
     */
    public static final String PATH_PROPERTY = "propertyPath";

    /** The optional property holding the value a required property must have, as a string. */
    public static final String VALUE_PROPERTY = "requiredValue";

    private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesPresentHealthCheck.class);

    @Reference
    private ResourceResolverFactory rrf;

    @Override
    public Result execute()
    {
        final FormattingResultLog result = new FormattingResultLog();
        int present = 0;
        int missing = 0;
        try (ResourceResolver resolver =
            this.rrf.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "healthcheck"))) {
            final Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                result.healthCheckError("The resource resolver is not backed by a JCR session");
                return new Result(result);
            }
            if (!session.nodeExists(CONFIGURATION_PATH)) {
                result.warn("No required properties configuration, please check the system integrity");
                return new Result(result);
            }
            final NodeIterator configurations = session.getNode(CONFIGURATION_PATH).getNodes();
            while (configurations.hasNext()) {
                final Node configuration = configurations.nextNode();
                try {
                    if (checkProperty(configuration, session, result)) {
                        present++;
                    } else {
                        missing++;
                    }
                } catch (final RepositoryException e) {
                    LOGGER.error("Unexpected exception while checking required properties: {}", e.getMessage(), e);
                    result.healthCheckError("Cannot run status check: {}", e.getMessage(), e);
                }
            }
        } catch (LoginException | RepositoryException e) {
            result.healthCheckError("Healthcheck module not set up properly: {}", e.getMessage());
        }
        result.info("{} required properties present" + (missing != 0 ? " and {} wrong/missing" : ""),
            present, missing);
        return new Result(result);
    }

    /**
     * Checks one required property, and adds a missing property or a wrong value to the result.
     *
     * @param configuration a child of {@link #CONFIGURATION_PATH}
     * @param session the service session to read the property with
     * @param result the log this check's outcome is added to
     * @return {@code true} if the property exists and, when a value is required, has that value
     * @throws RepositoryException if the configuration has no {@code propertyPath}, or either value cannot be read as
     *             a single string
     */
    private boolean checkProperty(final Node configuration, final Session session, final FormattingResultLog result)
        throws RepositoryException
    {
        final String propertyPath = configuration.getProperty(PATH_PROPERTY).getString();
        if (!session.propertyExists(propertyPath)) {
            result.critical("Required property not found: {}", propertyPath);
            return false;
        }
        if (!configuration.hasProperty(VALUE_PROPERTY)) {
            result.debug("Required property exists: {}", propertyPath);
            return true;
        }

        final String requiredValue = configuration.getProperty(VALUE_PROPERTY).getString();
        final String actualValue = session.getProperty(propertyPath).getString();
        if (!requiredValue.equals(actualValue)) {
            result.critical("Wrong value for required property {}: expected {}, got {}",
                propertyPath, requiredValue, actualValue);
            return false;
        }
        result.debug("Required property is correct: {}={}", propertyPath, actualValue);
        return true;
    }
}
