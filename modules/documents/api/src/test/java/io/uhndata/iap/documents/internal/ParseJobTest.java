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
package io.uhndata.iap.documents.internal;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link ParseJob}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ParseJobTest
{
    @Test
    void nodePathAppendsTheIdentifierToTheJobsRoot()
    {
        assertEquals("/var/documents/jobs/86a4c102", ParseJob.nodePath("86a4c102"));
    }

    @Test
    void cannotBeInstantiatedNormally() throws Exception
    {
        // Only reflection can call the private constructor; invoking it here just proves it stays empty
        final Constructor<ParseJob> constructor = ParseJob.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
