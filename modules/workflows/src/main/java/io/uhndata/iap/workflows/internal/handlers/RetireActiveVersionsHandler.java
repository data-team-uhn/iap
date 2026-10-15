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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Retires whichever versions are currently active beside the version being promoted, the one this step acts on:
 * their {@code active} tag becomes {@code retired}. The versions it compares are that version's siblings of the same
 * resource type, so it serves a workflow definition's versions and a schema's alike. The step comes before the
 * promoted version is tagged {@code active} in an activation workflow.
 *
 * <p>At most one version may be active at a time. Retiring and promoting happen as two steps of one workflow run,
 * committed together at its end, so a promotion that can't complete retires nothing and the invariant never
 * lapses.</p>
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
    public static final String HANDLER_NAME = "retireActiveVersions";

    /** The variable the retired versions' paths are left in. */
    public static final String RETIRED_VERSIONS_VARIABLE = "retiredVersions";

    /** The property a resource's own tags are stored in. */
    private static final String TAGS_PROPERTY = "tags";

    /** The lifecycle tag of a current version. */
    private static final String ACTIVE_TAG = "active";

    /** The lifecycle tag an outgoing version is given. */
    private static final String RETIRED_TAG = "retired";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource promoted = ExecutionHost.of(context);
        final Resource parent = promoted.getParent();
        if (parent == null) {
            throw new WorkflowDefinitionException(promoted.getPath() + " has no parent to hold other versions");
        }
        final List<String> retired = new ArrayList<>();
        for (final Resource sibling : parent.getChildren()) {
            if (sibling.getPath().equals(promoted.getPath())
                || !sibling.getResourceType().equals(promoted.getResourceType())) {
                continue;
            }
            // Read off the stored tags, as a version's own isActive does, rather than through the tags service
            if (!Arrays.asList(sibling.getValueMap().get(TAGS_PROPERTY, new String[0])).contains(ACTIVE_TAG)) {
                continue;
            }
            final Taggable tags = Objects.requireNonNull(sibling.adaptTo(Taggable.class),
                "Retiring a version takes the tags service, as tagging it active did");
            tags.untag(ACTIVE_TAG, true);
            tags.tag(RETIRED_TAG, true);
            retired.add(sibling.getPath());
        }
        context.setVariable(RETIRED_VERSIONS_VARIABLE, retired.toArray(new String[0]));
    }
}
