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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Gives the tests the expected startup page, so that they assert on what the gate serves rather than on the page's
 * wording, which is free to change.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class StartupPageFixture
{
    private StartupPageFixture()
    {
        // Utility class, not to be instantiated
    }

    /**
     * The startup page as the tested components see it: read from the classpath, not from the sources, so that
     * resource filtering can never make the expected and the actual page differ.
     *
     * @return the contents of the startup page
     * @throws IOException if the startup page cannot be read from the classpath, which means a broken build
     */
    static String stubPage() throws IOException
    {
        try (InputStream stub = Objects.requireNonNull(
            StartupPageFixture.class.getResourceAsStream("/custom_index.html"))) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stub.readAllBytes())).toString();
        }
    }
}
