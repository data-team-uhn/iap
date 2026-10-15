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
package io.uhndata.iap.workflows.internal.checks;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that no step of a system workflow waits for a person, since a system workflow runs to its end inside one
 * request.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class SystemWorkflowUserTaskCheck extends SystemWorkflowVersionCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        return asVersion(version).getActivities(null).isEmpty() ? List.of()
            : List.of("a system workflow cannot wait for a person, but it has a user task");
    }
}
