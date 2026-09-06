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
package io.uhndata.iap.statistics.internal;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Who looks after the metrics: who may see the ones reserved for administrators, and who may ask for
 * them to be worked out again.
 *
 * <p>
 * The question asked is whether they may <em>write</em> the definitions. That is deliberately not a
 * second list of who counts as an administrator: the repository already answers it, and a permission
 * and a list can drift apart where a permission and itself cannot.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Curators
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Curators.class);

    private Curators()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * Whether this reader looks after the metrics.
     *
     * @param request the request being answered
     * @return {@code true} if they do
     */
    static boolean includes(@NotNull final SlingJakartaHttpServletRequest request)
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            return false;
        }
        try {
            return session.hasPermission(request.getResource().getPath(), Session.ACTION_SET_PROPERTY);
        } catch (final RepositoryException e) {
            // Answering "no" leaves a curator seeing fewer metrics than they should, which is the
            // harmless way for this to be wrong
            LOGGER.warn("Could not tell whether {} may curate the metrics: {}", session.getUserID(),
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(Curators.class, "includes"));
            return false;
        }
    }
}
