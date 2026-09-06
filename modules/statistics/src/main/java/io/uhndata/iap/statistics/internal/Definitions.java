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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.statistics.models.Metric;

/**
 * Reaching the metric definitions: where they live, and the session they are read through.
 *
 * <p>
 * The session is a privileged one, because a metric is deliberately an aggregate over records its reader
 * may not see one at a time, and because the numbers are kept beside the definitions where only this
 * module may read them. Nothing here decides who may see what — that is decided once, at the HTTP
 * boundary, by each metric's access level.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Definitions
{
    /** Where the metric definitions live, and with them the numbers they last produced. */
    static final String ROOT = "/Statistics";

    private static final String SUBSERVICE = "statistics";

    private Definitions()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * Opens the privileged session everything here is read and written through.
     *
     * @param factory the factory to ask
     * @return a session the caller must close
     * @throws LoginException when the service user is not available
     */
    @NotNull
    static ResourceResolver open(@NotNull final ResourceResolverFactory factory) throws LoginException
    {
        return factory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    /**
     * Every defined metric, in the order they are meant to be shown.
     *
     * @param resolver the session to read through
     * @return the definitions, empty when there are none — or when the tree they live in is missing,
     *         which is what an instance looks like before this module's content has been installed
     */
    @NotNull
    static List<Metric> all(@NotNull final ResourceResolver resolver)
    {
        final Resource root = resolver.getResource(ROOT);
        if (root == null) {
            return List.of();
        }
        final List<Metric> defined = new ArrayList<>();
        root.getChildren().forEach(child -> {
            final Metric metric = child.adaptTo(Metric.class);
            if (metric != null) {
                defined.add(metric);
            }
        });
        defined.sort(Metric.displayOrder());
        return defined;
    }
}
