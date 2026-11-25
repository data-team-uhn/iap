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
package io.uhndata.iap.datarequirement.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Records which fields a submitter chose for one data requirement of their submission. It performs the
 * {@code saveDataSelection} workflow, which runs on the submission and names the requirement in its payload.
 *
 * <p>Each save replaces the whole selection. Clearing it or ticking a whole collection changes many fields at once,
 * and the complete list carries that without separate messages for additions and removals.</p>
 *
 * <p>The keys are checked against the catalogue version the requirement names.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveDataSelectionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "saveDataSelection";

    /** The payload entry naming the requirement being answered. */
    static final String REQUIREMENT_PARAMETER = "requirement";

    /** The payload entry carrying the chosen field keys. */
    static final String FIELDS_PARAMETER = "fields";

    /** Where a selection records the chosen field keys. */
    private static final String FIELDS_PROPERTY = "fields";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Submission submission = readSubmission(context.getTarget());
        checkMaySave(submission, context.getActor());
        final DataRequirement requirement = getRequirement(submission, context);
        final Selection selection = getSelection(submission, requirement);
        final String[] chosen = getChosenKeys(context);
        checkOffered(requirement.getCatalogueVersion(), chosen);

        VersioningUtils.checkOut(context.getTarget());
        final Resource stored = getResource(context.getTarget(), selection);
        recordFields(stored, chosen);
        context.recordRole(stored, "chose data for " + requirement.getLabel());
    }

    /**
     * Reads the task's target as a submission.
     *
     * @param target the resource the task acts on
     * @return the submission
     * @throws WorkflowDefinitionException when the workflow points the task at anything else
     */
    private Submission readSubmission(final Resource target) throws WorkflowDefinitionException
    {
        if (!target.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The " + HANDLER_NAME + " task only applies to submissions, not to "
                + target.getResourceType());
        }
        return Objects.requireNonNull(target.adaptTo(Submission.class), "A submission adapts to its model");
    }

    /**
     * Refuses a save that is not the submitter's own, or that comes too late.
     *
     * @param submission the submission being answered
     * @param actor the repository user id of the person who sent the event
     * @throws NotAuthorizedException when somebody other than the submission's creator is choosing
     * @throws InvalidStateException when the submission is no longer a draft
     */
    private void checkMaySave(final Submission submission, final String actor)
        throws NotAuthorizedException, InvalidStateException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service
        // user for everything it writes
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may choose data for it");
        }
        if (!submission.isDraft()) {
            throw new InvalidStateException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The requirement being answered: a data requirement of the submission's own schema version that applies to it.
     *
     * @param submission the submission being answered
     * @param context the executing task's context
     * @return the data requirement
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission is not asked
     */
    private DataRequirement getRequirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final String named = Payloads.requireText(context.getEvent(), REQUIREMENT_PARAMETER,
            "The selection does not say which requirement it answers");
        return submission.getSchemaVersion().getChildren(DataRequirement.RESOURCE_TYPE, DataRequirement.class)
            .stream()
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            // A requirement whose condition does not hold is not asked, and the form does not show it
            .filter(submission::isApplicable)
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no data requirement " + named + " in this request"));
    }

    /**
     * The selection answering the requirement, which the workflow's parts step made before this one.
     *
     * @param submission the submission being answered
     * @param requirement the requirement being answered
     * @return the selection
     */
    private Selection getSelection(final Submission submission, final DataRequirement requirement)
    {
        return Objects.requireNonNull(requirement.findSelectionIn(submission),
            "The parts step gives every data requirement that applies a selection");
    }

    /**
     * The field keys the event carries. Sending none, or one empty value, clears the selection.
     *
     * @param context the executing task's context
     * @return the chosen field keys, each once, in the order sent; empty when the selection is being cleared
     */
    private String[] getChosenKeys(final WorkflowTaskContext context)
    {
        final Object submitted = context.getEvent().get(FIELDS_PARAMETER);
        final Stream<String> given = submitted instanceof final String[] keys ? Arrays.stream(keys)
            : Stream.ofNullable(submitted).map(String::valueOf);
        return given.filter(key -> !key.isBlank()).distinct().toArray(String[]::new);
    }

    /**
     * Refuses a key the version does not offer.
     *
     * @param version the catalogue version the keys are checked against
     * @param chosen the submitted field keys
     * @throws InvalidPayloadException when a key names no field in that version
     */
    private void checkOffered(final CatalogueVersion version, final String[] chosen)
        throws InvalidPayloadException
    {
        if (chosen.length == 0) {
            return;
        }
        final Set<String> offered = version.getFieldKeys();
        final List<String> unknown = Arrays.stream(chosen)
            .filter(key -> !offered.contains(key))
            .toList();
        if (!unknown.isEmpty()) {
            throw new InvalidPayloadException("This catalogue does not offer " + String.join(", ", unknown));
        }
    }

    /**
     * The selection's resource, through the session the task writes with.
     *
     * @param target the submission the selection belongs to
     * @param selection the selection
     * @return its resource
     */
    private Resource getResource(final Resource target, final Selection selection)
    {
        return Objects.requireNonNull(target.getResourceResolver().getResource(selection.getPath()),
            "A selection the submission lists can be read through the same session");
    }

    /**
     * Replaces the chosen keys.
     *
     * @param stored the selection's resource
     * @param chosen the keys to record
     */
    private void recordFields(final Resource stored, final String[] chosen)
    {
        Objects.requireNonNull(stored.adaptTo(ModifiableValueMap.class),
            "A selection read through the engine's own resolver is always modifiable").put(FIELDS_PROPERTY, chosen);
    }
}
