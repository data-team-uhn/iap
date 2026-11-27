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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * A schema version may only be published when its workflow fills in each of its generated documents: a
 * {@code generateDocument} step names the requirement, as the step itself finds it, by name or by path. A generated
 * document never counts as missing while the request is filled in, so without such a step it would go on without
 * one, and nothing would say so.
 *
 * <p>It only checks that the step is there, not that every path through the workflow reaches it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class GenerateDocumentStepCheck implements ValidityCheck
{
    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(SchemaVersion.RESOURCE_TYPE);
    }

    @Override
    public List<String> check(final Resource target)
    {
        final SchemaVersion version = Objects.requireNonNull(target.adaptTo(SchemaVersion.class),
            "A sch:SchemaVersion resource always adapts to its model");
        final List<DocumentRequirement> generated = version.getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(DocumentRequirement::isGenerated)
            .toList();
        if (generated.isEmpty()) {
            return List.of();
        }
        final WorkflowVersion workflow = version.getWorkflow();
        if (workflow == null) {
            return generated.stream()
                .map(requirement -> describe(requirement) + " is generated, but there is no workflow to generate it")
                .toList();
        }
        final List<Object> named = workflow.getActivities(GenerateDocumentHandler.HANDLER_NAME).stream()
            .map(activity -> activity.get(GenerateDocumentHandler.REQUIREMENT_PARAMETER))
            .toList();
        return generated.stream()
            .filter(requirement -> !named.contains(requirement.getName()) && !named.contains(requirement.getPath()))
            .map(requirement -> describe(requirement) + " is generated, but no generateDocument step of the workflow "
                + "names it, so it would never be filled in")
            .toList();
    }

    private static String describe(final DocumentRequirement requirement)
    {
        return "\"" + requirement.getLabel() + "\"";
    }
}
