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
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that a version has exactly one start event: with none, no instance can start from it, and with several, an
 * event could start it in more than one place.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class StartEventCheck implements ValidityCheck
{
    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(WorkflowVersion.RESOURCE_TYPE);
    }

    @Override
    public List<String> check(final Resource version)
    {
        final int starts = Objects.requireNonNull(version.adaptTo(WorkflowVersion.class),
            "It applies to workflow versions only").getStartEvents().size();
        return starts == 1 ? List.of()
            : List.of("it needs exactly one start event, and has " + starts);
    }
}
