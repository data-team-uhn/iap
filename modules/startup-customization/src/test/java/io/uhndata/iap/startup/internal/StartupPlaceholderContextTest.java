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
package io.uhndata.iap.startup.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StartupPlaceholderContext}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StartupPlaceholderContextTest
{
    @Test
    void isAnEmptyServletContext() throws Exception
    {
        final StartupPlaceholderContext context = new StartupPlaceholderContext();

        // The plain ServletContextHelper defaults: no resources of its own, every request allowed through
        assertNull(context.getResource("/anything"));
        assertTrue(context.handleSecurity(null, null));
    }
}
