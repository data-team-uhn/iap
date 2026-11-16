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
package io.uhndata.iap.links.internal;

import java.util.Map;
import java.util.function.Consumer;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Makes a change to the links as the {@code iap-links} service user, and commits it. The listeners use it after a
 * user's commit, when nobody is left to report a failure to. A failure is logged and recorded against the listener.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LinksServiceUser
{
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, LinkManagerImpl.SUBSERVICE_NAME);

    private final ResourceResolverFactory resolverFactory;

    private final Class<?> reporter;

    private final Logger logger;

    /**
     * Basic constructor.
     *
     * @param resolverFactory the factory to log the service user in through
     * @param reporter the component that failures are logged and recorded against
     */
    LinksServiceUser(final ResourceResolverFactory resolverFactory, final Class<?> reporter)
    {
        this.resolverFactory = resolverFactory;
        this.reporter = reporter;
        this.logger = LoggerFactory.getLogger(reporter);
    }

    /**
     * Makes a change in a new service session, and commits it if it changed anything.
     *
     * @param path the path of the resource the change is about, for the logs
     * @param operation the name of the change, such as {@code completeBacklink}, for the logs
     * @param change writes into the service resolver it is given, and leaves the commit to this method
     */
    void commit(final String path, final String operation, final Consumer<ResourceResolver> change)
    {
        try (ResourceResolver serviceResolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            change.accept(serviceResolver);
            if (serviceResolver.hasChanges()) {
                serviceResolver.commit();
            }
        } catch (final LoginException e) {
            this.logger.warn("Failed to get a service session for {} on {}: {}", operation, path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(this.reporter, "serviceLogin").about(path));
        } catch (final PersistenceException e) {
            this.logger.warn("Failed to commit {} on {}: {}", operation, path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(this.reporter, operation).about(path));
        }
    }
}
