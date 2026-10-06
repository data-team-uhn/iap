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
package io.uhndata.iap.workflows.spi;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the defaults {@link WorkflowTaskContext} gives a context that does not override them.
 *
 * @version $Id$
 * @since 0.1.0
 */
class WorkflowTaskContextTest
{
    private final Resource host = Mockito.mock(Resource.class);

    private final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

    @Test
    void startsPlainlyWhenNothingIsToBeReplaced() throws Exception
    {
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class, Mockito.CALLS_REAL_METHODS);

        context.startWorkflow(this.host, this.version, false);

        Mockito.verify(context).startWorkflow(this.host, this.version);
    }

    // A context that cannot cancel an instance says so, rather than starting a second one beside it
    @Test
    void refusesToReplaceWhenItCannot()
    {
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class,
            () -> context.startWorkflow(this.host, this.version, true));
    }
}
