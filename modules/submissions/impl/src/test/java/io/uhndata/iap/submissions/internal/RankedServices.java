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
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.stream.IntStream;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.osgi.framework.Constants;

/**
 * Registers services so that a component binding all of them sees them in a chosen order. The framework sorts such
 * a list by ascending {@code service.ranking}, so each service is ranked by its place in the given list.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RankedServices
{
    private RankedServices()
    {
    }

    /**
     * Registers the services, ranked in the order given.
     *
     * @param <T> the service type
     * @param context the mock context to register them in
     * @param type the interface they are registered under
     * @param services the services, first to be consulted first
     */
    public static <T> void registerInOrder(final SlingContext context, final Class<T> type, final List<T> services)
    {
        IntStream.range(0, services.size())
            .forEach(rank -> context.registerService(type, services.get(rank), Constants.SERVICE_RANKING, rank));
    }
}
