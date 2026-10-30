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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Field;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records which fields a submitter chose: what the save workflow on a data requirement
 * performs.
 *
 * <p>Each save replaces the whole selection. Clearing it and ticking a whole collection both change many fields at
 * once, and the complete list says that without a vocabulary of additions and removals.</p>
 *
 * <p><strong>The catalogue version is bound once and never changed.</strong> The first save binds the selection to
 * the catalogue's active version. Every later save is checked against that same one. Moving a submitter to a newer
 * version would change what their earlier choices refer to.</p>
 *
 * <p><strong>Both rules about who may do this are checked here.</strong> The actor must be the person the engine
 * recorded as having raised the submission. The submission must still be a draft. The start event also declares
 * {@code @creator}, so the engine offers saving only to that person. The first rule is checked again because
 * administrators pass every declaration.</p>
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

    /** Where a selection records what it answers. */
    private static final String FULFILLS_PROPERTY = "fulfills";

    /** Where a selection records which version it was chosen from. */
    private static final String CATALOGUE_VERSION_PROPERTY = "catalogueVersion";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The data selection workflow only applies to submissions");
        checkMaySave(submission, context.getActor());

        final DataRequirement requirement = getRequirement(submission, context);
        final Selection existing = Selections.findSelection(submission, requirement);
        final CatalogueVersion bound = existing == null ? null : existing.getCatalogueVersion();
        final CatalogueVersion version = bound == null ? requireActiveVersion(requirement) : bound;
        final String[] chosen = getChosenKeys(context);
        checkOffered(version, chosen);

        VersioningUtils.checkOut(target);
        final Resource selection = existing == null ? createSelection(target, requirement)
            : Objects.requireNonNull(target.getResourceResolver().getResource(existing.getPath()),
                "A selection the submission lists can be read through the same session");
        if (bound == null) {
            ReferenceUtils.setReference(selection, CATALOGUE_VERSION_PROPERTY, version);
        }
        Objects.requireNonNull(selection.adaptTo(ModifiableValueMap.class),
            "A selection read through the engine's own resolver is always modifiable").put(FIELDS_PROPERTY, chosen);
    }

    /**
     * Refuses a save that is not the submitter's own, or that comes too late.
     *
     * @param submission the submission being answered
     * @param actor the id of the user who sent the event
     * @throws NotAuthorizedException when somebody else is choosing
     * @throws InvalidStateException when it is no longer a draft
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
     * The requirement being answered, which has to be one this submission's own schema asks for.
     *
     * <p>Resolved through the schema version rather than by trusting the path. Otherwise a caller naming a
     * requirement of another schema could answer something this submission was never asked.</p>
     *
     * @param submission the submission being answered
     * @param context the executing task's context
     * @return a data requirement of the submission's own schema version
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission lacks
     */
    private DataRequirement getRequirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final String named = Payloads.requireText(context.getEvent(), REQUIREMENT_PARAMETER,
            "The selection does not say which requirement it answers");
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DataRequirement.class::isInstance)
            .map(DataRequirement.class::cast)
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no data requirement " + named + " in this request"));
    }

    /**
     * The version the first save binds a selection to: the catalogue's active one.
     *
     * @param requirement the requirement being answered
     * @return the catalogue version tagged {@code active}
     * @throws InvalidPayloadException when the catalogue has no active version
     */
    private CatalogueVersion requireActiveVersion(final DataRequirement requirement) throws InvalidPayloadException
    {
        final CatalogueVersion current = requirement.getCurrentVersion();
        if (current == null) {
            throw new InvalidPayloadException("The catalogue this request asks about has published no version");
        }
        return current;
    }

    /**
     * The field keys the event carries.
     *
     * @param context the executing task's context
     * @return the chosen field keys, empty when the selection is being cleared
     */
    private String[] getChosenKeys(final WorkflowTaskContext context)
    {
        final Object submitted = context.getEvent().get(FIELDS_PARAMETER);
        if (submitted == null) {
            // Choosing nothing is a legitimate save: it is how a selection is cleared
            return new String[0];
        }
        return submitted instanceof String[] ? (String[]) submitted : new String[] {String.valueOf(submitted)};
    }

    /**
     * Refuses a key the recorded version does not offer.
     *
     * <p>Without this a caller could store any string at all. The check is against the <em>bound</em> version: a
     * key valid in the catalogue's active version may not exist in the one this request is answered from.</p>
     *
     * @param version the catalogue version the selection is bound to, or is about to be
     * @param chosen the submitted field keys
     * @throws InvalidPayloadException when a key names no field in that version
     */
    private void checkOffered(final CatalogueVersion version, final String[] chosen)
        throws InvalidPayloadException
    {
        final Set<String> offered = version.getFields().stream()
            .map(Field::getKey)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        final List<String> unknown = Arrays.stream(chosen)
            .filter(key -> !offered.contains(key))
            .toList();
        if (!unknown.isEmpty()) {
            throw new InvalidPayloadException(
                "This catalogue does not offer " + String.join(", ", unknown));
        }
    }

    /**
     * Creates the selection for a requirement whose part has not been made yet.
     *
     * @param target the submission the selection belongs to
     * @param requirement the requirement being answered
     * @return the new, still unbound selection
     * @throws PersistenceException when the selection cannot be written
     */
    private Resource createSelection(final Resource target, final DataRequirement requirement)
        throws PersistenceException
    {
        final Resource selection = target.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE_PROPERTY, Selection.NODE_TYPE));
        ReferenceUtils.setReference(selection, FULFILLS_PROPERTY, requirement);
        return selection;
    }
}
