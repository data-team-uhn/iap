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
package io.uhndata.iap.workflows.testing;

import org.apache.sling.testing.mock.osgi.context.AbstractContextPlugin;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.workflows.api.WorkflowEngine;

/**
 * Installs {@link WorkflowPlugins#MOCK_ENGINE}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class MockEnginePlugin extends AbstractContextPlugin<SlingContextImpl>
{
    @Override
    public void afterSetUp(final SlingContextImpl context)
    {
        context.registerService(WorkflowEngine.class, Mockito.mock(WorkflowEngine.class),
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
    }
}
