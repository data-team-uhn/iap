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
package io.uhndata.iap.deletion.internal.handlers;

import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code delete} service task: deletes the event's target through the {@link DeletionService}. It archives the
 * target unless the activity sets {@code permanent}. It never deletes recursively, since a workflow may delete only
 * the entity it targets. Content that something references, or that a guard protects, stays, and the step fails
 * with an {@link InvalidStateException}. The archive records the user the workflow acts for as having deleted it.
 *
 * <p>The deletion is made {@link DeletionService#deleteWithin within the delivery}: the engine commits it with the
 * delivery's other changes, or reverts it with them. The nodes it checks out are checked back in after that
 * commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class DeleteTaskHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "delete";

    /** The activity property that, when {@code true}, removes the target for good rather than archiving it. */
    private static final String PERMANENT_PARAMETER = "permanent";

    @Reference
    private DeletionService deletionService;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Resource target = context.getTarget();
        final boolean permanent =
            Boolean.parseBoolean(String.valueOf(context.getActivity().get(PERMANENT_PARAMETER)));
        context.recordRole(target, permanent ? "purged" : "archived");
        final DeletionResult result;
        try {
            result = this.deletionService.deleteWithin(target,
                DeletionOptions.of(DeletionOptions.REFUSE_IF_REFERENCED, permanent).onBehalfOf(context.getActor()));
        } catch (final DeletionException e) {
            throw new WorkflowFailedException("Could not delete " + target.getPath(), e);
        }
        switch (result.getStatus()) {
            case ARCHIVED, DELETED -> result.getCheckedOut().forEach(context::checkInAfterCommit);
            case VETOED -> throw new InvalidStateException(result.getImpact().getVetoes().stream()
                .map(Veto::getReason)
                .distinct()
                .collect(Collectors.joining("; ")));
            case REQUIRES_CONFIRMATION -> throw new InvalidStateException(result.getImpact().getSummary());
            default -> throw new NotAuthorizedException("The workflow may not delete " + target.getPath());
        }
    }
}
