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

import org.apache.felix.hc.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DuplicateJarsHealthCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DuplicateJarsHealthCheckTest
{
    private final DuplicateJarsHealthCheck check = new DuplicateJarsHealthCheck();

    private final BundleContext context = Mockito.mock(BundleContext.class);

    @BeforeEach
    void setUp()
    {
        this.check.activate(this.context);
    }

    @Test
    void passesWhenAllBundlesAreUnique()
    {
        final Bundle first = bundle("org.example.first");
        final Bundle second = bundle("org.example.second");
        Mockito.when(this.context.getBundles()).thenReturn(new Bundle[] { first, second });

        final Result result = this.check.execute();

        assertTrue(result.isOk());
        assertEquals(Result.Status.OK, result.getStatus());
    }

    @Test
    void warnsOnDuplicateSymbolicNames()
    {
        final Bundle unique = bundle("org.example.first");
        final Bundle duplicated = bundle("org.example.duplicated");
        final Bundle alsoDuplicated = bundle("org.example.duplicated");
        Mockito.when(this.context.getBundles()).thenReturn(new Bundle[] { unique, duplicated, alsoDuplicated });

        final Result result = this.check.execute();

        assertEquals(Result.Status.WARN, result.getStatus());
    }

    @Test
    void reportsErrorWhenNoBundlesAreFound()
    {
        Mockito.when(this.context.getBundles()).thenReturn(null);
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());

        Mockito.when(this.context.getBundles()).thenReturn(new Bundle[0]);
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    private Bundle bundle(final String symbolicName)
    {
        final Bundle bundle = Mockito.mock(Bundle.class);
        Mockito.when(bundle.getSymbolicName()).thenReturn(symbolicName);
        return bundle;
    }
}
