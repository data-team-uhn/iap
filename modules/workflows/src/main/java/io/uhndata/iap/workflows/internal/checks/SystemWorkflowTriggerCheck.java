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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that a version of a system workflow says what triggers it: the type of resource it acts on, in
 * {@code targetResourceType}, and the message that starts it. Without both, no event can ever reach it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class SystemWorkflowTriggerCheck extends SystemWorkflowVersionCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        final WorkflowVersion model = asVersion(version);
        final List<String> problems = new ArrayList<>();
        if (StringUtils.isBlank(model.getTargetResourceType())) {
            problems.add("a system workflow must name the type it acts on in targetResourceType");
        }
        if (model.getStartEvents().stream().map(StartEvent::getMessageName).anyMatch(StringUtils::isBlank)) {
            problems.add("a system workflow must be started by a named message");
        }
        return problems;
    }
}
