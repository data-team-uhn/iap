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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Retires whichever versions of the target's workflow are currently active, to make room for the target being
 * promoted in their place. The step before {@code setVersionState} in the activation workflow.
 *
 * <p>At most one version of a definition may be active at a time. Retiring and promoting happen as two steps of
 * one workflow run, committed together at its end, so a promotion that can't complete retires nothing and the
 * invariant never lapses.</p>
 *
 * <p>Every active version is retired, not only the first one found, since more than one being active is already
 * a broken invariant that a promotion is the natural moment to repair.</p>
 *
 * <p>The retired paths are recorded in the {@code retiredVersions} variable, for a later step or the channel that
 * fired the event.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RetireActiveVersionsHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "retireActiveVersions";

    /** The variable the retired versions' paths are left in. */
    public static final String RETIRED_VERSIONS = "retiredVersions";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource promoted = context.getTarget();
        final List<String> retired = new ArrayList<>();
        for (final Resource sibling : VersionEdits.definitionOf(promoted).getChildren()) {
            if (sibling.getPath().equals(promoted.getPath())
                || !sibling.isResourceType(WorkflowVersion.RESOURCE_TYPE)) {
                continue;
            }
            final WorkflowVersion version = sibling.adaptTo(WorkflowVersion.class);
            if (version == null || version.getState() != WorkflowVersion.State.ACTIVE) {
                continue;
            }
            Objects.requireNonNull(sibling.adaptTo(ModifiableValueMap.class),
                "The engine can always write what it can read")
                .put(VersionEdits.STATE, WorkflowVersion.State.RETIRED.name());
            retired.add(sibling.getPath());
        }
        context.setVariable(RETIRED_VERSIONS, retired.toArray(new String[0]));
    }
}
