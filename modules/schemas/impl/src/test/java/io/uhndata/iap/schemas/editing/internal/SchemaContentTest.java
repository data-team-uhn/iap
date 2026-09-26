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
package io.uhndata.iap.schemas.editing.internal;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.LifecycleState;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SchemaContent}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SchemaContentTest
{
    @Test
    void reportsContentThatCannotBeTagged()
    {
        final Resource target = Mockito.mock(Resource.class);
        Mockito.when(target.getPath()).thenReturn("/Schemas/study");

        assertThrows(PersistenceException.class, () -> SchemaContent.setLifecycle(target, LifecycleState.ACTIVE));
    }
}
