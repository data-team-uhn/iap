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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Field;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records which fields a submitter chose: what the save workflow on a data requirement
 * performs.
 *
 * <p>The whole selection is replaced rather than changed field by field. Clearing the selection and ticking a
 * whole collection both move many fields at once, so a protocol of additions and removals would have to describe
 * that; sending what the selection now is says the same thing without a vocabulary for it.</p>
 *
 * <p><strong>The catalogue version is bound once and never moved.</strong> The first save records whichever
 * version the catalogue is publishing then, and every later save is checked against that same one — which is what
 * keeps a filed request meaning what it meant, whatever has been published since. A submitter who wants the newer
 * catalogue cannot be given it silently, because it would change what their earlier choices refer to.</p>
 *
 * <p><strong>Both rules about who may do this are enforced here, in full.</strong> The engine executes with its
 * own privileged session, so nothing downstream will refuse anyone: whatever is not checked in a handler is
 * allowed. They are that the actor is the person the engine recorded as having raised the submission, and that
 * the submission is still a draft.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveDataSelectionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveDataSelection";

    /** The payload entry naming the requirement being answered. */
    static final String REQUIREMENT = "requirement";

    /** The payload entry carrying the chosen field keys. */
    static final String FIELDS = "fields";

    /** Where a selection records what it answers. */
    private static final String FULFILLS = "fulfills";

    /** Where a selection records which version it was chosen from. */
    private static final String CATALOGUE_VERSION = "catalogueVersion";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The data selection workflow only applies to submissions");
        checkMaySave(submission, context.getActor());

        final DataRequirement requirement = requirement(submission, context);
        final Selection existing = Selections.of(submission, requirement);
        final CatalogueVersion version = version(requirement, existing);
        final String[] chosen = keys(context);
        checkOffered(version, chosen);

        if (existing == null) {
            create(context.getResourceResolver(), target, requirement, version, chosen);
        } else {
            replace(context.getResourceResolver(), existing, chosen);
        }
    }

    /**
     * Refuses a save that is not the submitter's own, or that comes too late.
     *
     * @param submission the submission being answered
     * @param actor the user whose action this is
     * @throws NotAuthorizedException when somebody else is choosing, or it is no longer a draft
     */
    private void checkMaySave(final Submission submission, final String actor) throws NotAuthorizedException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service
        // user for everything it writes
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may choose data for it");
        }
        if (!submission.isDraft()) {
            throw new NotAuthorizedException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The requirement being answered, which has to be one this submission's own schema asks for.
     *
     * <p>Resolved through the schema version rather than by trusting the path, or a caller naming a requirement of
     * some other schema could answer something this submission was never asked.</p>
     *
     * @param submission the submission being answered
     * @param context the executing task's context
     * @return the requirement
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission lacks
     */
    private DataRequirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final Object named = context.getEvent().get(REQUIREMENT);
        if (!(named instanceof String) || ((String) named).isBlank()) {
            throw new InvalidPayloadException("The selection does not say which requirement it answers");
        }
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DataRequirement.class::isInstance)
            .map(DataRequirement.class::cast)
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no data requirement " + named + " in this request"));
    }

    /**
     * The version this save is recorded against: the one already bound, or the catalogue's current one for a
     * selection being made for the first time.
     *
     * @param requirement the requirement being answered
     * @param existing what has been chosen already, or {@code null} if nothing has
     * @return the version to record against
     * @throws InvalidPayloadException when there is no version to choose from at all
     */
    private CatalogueVersion version(final DataRequirement requirement, final Selection existing)
        throws InvalidPayloadException
    {
        final CatalogueVersion bound = existing == null ? null : existing.getCatalogueVersion();
        if (bound != null) {
            return bound;
        }
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
     * @return the chosen keys, empty when the selection is being cleared
     */
    private String[] keys(final WorkflowTaskContext context)
    {
        final Object submitted = context.getEvent().get(FIELDS);
        if (submitted == null) {
            // Choosing nothing is a legitimate save: it is how a selection is cleared
            return new String[0];
        }
        return submitted instanceof String[] ? (String[]) submitted : new String[] {String.valueOf(submitted)};
    }

    /**
     * Refuses a key the recorded version does not offer.
     *
     * <p>Without this a caller could store any string at all, and a selection would stop being a set of fields
     * that exist. The check is against the <em>bound</em> version, which is also what makes the binding meaningful:
     * a key valid in today's catalogue is not necessarily valid in the one this request is being answered from.</p>
     *
     * @param version the version this selection is recorded against
     * @param chosen the submitted keys
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
     * Records a selection for the first time, binding it to the version it was chosen from.
     *
     * @param resolver the resolver the engine is writing through
     * @param target the submission the selection belongs to
     * @param requirement the requirement being answered
     * @param version the version being bound
     * @param chosen the chosen keys
     * @throws PersistenceException when the selection cannot be written
     */
    private void create(final ResourceResolver resolver, final Resource target, final DataRequirement requirement,
        final CatalogueVersion version, final String[] chosen) throws PersistenceException
    {
        // A UUID, because a selection has no name of its own: what it answers is a reference, not a label, and a
        // node named after the requirement would have to be renamed if the requirement ever were
        final Resource selection = resolver.create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "datareq:Selection", FIELDS, chosen));
        final Node node = Objects.requireNonNull(selection.adaptTo(Node.class),
            "A freshly created selection is always backed by a JCR node");
        reference(node, resolver, FULFILLS, requirement.getPath());
        reference(node, resolver, CATALOGUE_VERSION, version.getPath());
    }

    /**
     * Replaces what an existing selection holds, leaving the version it is bound to alone.
     *
     * @param resolver the resolver the engine is writing through
     * @param existing the selection already recorded
     * @param chosen the chosen keys
     * @throws PersistenceException when the selection cannot be written
     */
    private void replace(final ResourceResolver resolver, final Selection existing, final String[] chosen)
        throws PersistenceException
    {
        final Resource resource = resolver.getResource(existing.getPath());
        if (resource == null) {
            throw new PersistenceException("Could not read the selection being replaced");
        }
        final ModifiableValueMap properties = Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A selection read through the engine's own resolver is always modifiable");
        properties.put(FIELDS, chosen);
    }

    /**
     * Writes one REFERENCE property by the path of its target.
     *
     * @param selection the node of the selection
     * @param resolver the resolver to read the target back through
     * @param property the property to write
     * @param path the path of the node being referenced
     * @throws PersistenceException when the repository refuses the reference
     */
    private void reference(final Node selection, final ResourceResolver resolver, final String property,
        final String path) throws PersistenceException
    {
        final Resource resource = resolver.getResource(path);
        if (resource == null) {
            throw new PersistenceException("Could not read " + path);
        }
        final Node target = Objects.requireNonNull(resource.adaptTo(Node.class),
            "A node read out of the repository is always backed by a JCR node");
        try {
            // A REFERENCE cannot be written as a string through the resolver: Sling stores it as a STRING and
            // Oak's type validation refuses the commit rather than coercing it
            selection.setProperty(property, target);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference " + path, e);
        }
    }
}
