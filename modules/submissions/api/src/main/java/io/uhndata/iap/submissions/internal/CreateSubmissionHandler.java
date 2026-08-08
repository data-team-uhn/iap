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
package io.uhndata.iap.submissions.internal;

import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that raises a new submission: what the bootstrap workflow on {@code /Submissions} performs.
 * The event's {@code title} names the submission, and its {@code schemaVersion} — the <em>path</em> of a
 * {@code sch:SchemaVersion} — says what is being submitted against; the created submission holds a real
 * reference to it, and starts in the {@code draft} status the node type declares.
 *
 * <p>This lives in the submissions module, not the workflows one, on purpose: what it takes to create a
 * submission — an <em>active</em> schema version — is submissions business, plugged into the engine through the
 * {@link ServiceTaskHandler} extension point like any project's own behavior would be.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateSubmissionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "createSubmission";

    /** The payload entry naming the created submission. */
    private static final String TITLE = "title";

    /** The payload entry pointing at the schema version being submitted against. */
    private static final String SCHEMA_VERSION = "schemaVersion";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object title = context.getEvent().get(TITLE);
        if (!(title instanceof String) || ((String) title).isBlank()) {
            throw new InvalidPayloadException("A title is required");
        }
        final Resource version = resolveSchemaVersion(context);
        final Resource created = context.getResourceResolver().create(context.getTarget(),
            freeName(context.getTarget(), (String) title),
            Map.of("jcr:primaryType", "sub:Submission", TITLE, title));
        reference(created, version);
        context.setVariable(WorkflowResult.CREATED_PATH, created.getPath());
    }

    /**
     * Points the fresh submission at its schema version with a real {@code REFERENCE}. This has to go through
     * the JCR API: a plain string property would carry the right identifier but the wrong type, and the strict
     * {@code sub:Submission} definition rejects it at commit.
     *
     * @param created the submission just created
     * @param version the vetted schema version
     * @throws PersistenceException when the repository refuses the reference
     */
    private void reference(final Resource created, final Resource version) throws PersistenceException
    {
        final Node submission = Objects.requireNonNull(created.adaptTo(Node.class),
            "A freshly created submission is always backed by a JCR node");
        final Node target = Objects.requireNonNull(version.adaptTo(Node.class),
            "A vetted schema version is always backed by a JCR node");
        try {
            submission.setProperty(SCHEMA_VERSION, target);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the schema version", e);
        }
    }

    /**
     * Resolves and vets the schema version the payload points at: it must exist, be a schema version rather than
     * whatever else happens to sit at that path, and both it and its schema must be active — which is where "no
     * new submissions may be created from an inactive version" is actually enforced.
     *
     * <p>Every one of those checks has to be made here. The lookup runs on the engine's privileged session, so
     * nothing is hidden from it and nothing will be refused on the caller's behalf; being allowed to raise a
     * submission is a question the start event already answered, and it is not the same question as which schema
     * versions this particular user should be able to answer. When the platform can express the narrower rule —
     * institutions, study teams — it belongs in the definition next to the performers, not here.</p>
     *
     * @param context the executing task's context
     * @return the resolved schema version's resource
     * @throws InvalidPayloadException when the payload does not point at an active schema version
     */
    private Resource resolveSchemaVersion(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object path = context.getEvent().get(SCHEMA_VERSION);
        if (!(path instanceof String) || ((String) path).isBlank()) {
            throw new InvalidPayloadException("A schemaVersion is required: the path of the schema version this"
                + " submission answers");
        }
        final Resource resource = context.getResourceResolver().getResource((String) path);
        if (resource == null || !resource.isResourceType(SchemaVersion.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("There is no schema version at " + path);
        }
        final SchemaVersion version = Objects.requireNonNull(resource.adaptTo(SchemaVersion.class),
            "A sch:SchemaVersion resource failed to adapt to its model");
        final Schema schema = version.getSchema();
        if (!version.isActive() || schema == null || !schema.isActive()) {
            throw new InvalidPayloadException(
                "The schema version at " + path + " is not accepting new submissions");
        }
        return resource;
    }

    /**
     * Derives a free node name from the title, translating naming problems into payload refusals.
     *
     * @param parent the submissions homepage the submission will be created under
     * @param title the human-given title
     * @return a free, camel-cased name
     * @throws InvalidPayloadException when the title yields no usable name, or every variant is taken
     */
    private String freeName(final Resource parent, final String title) throws InvalidPayloadException
    {
        final String base = NodeNameUtils.camelCase(title);
        if (base.isEmpty()) {
            throw new InvalidPayloadException("The title must contain at least one letter or digit");
        }
        final String name = NodeNameUtils.findFreeName(parent, base);
        if (name == null) {
            throw new InvalidPayloadException(
                "Too many submissions are already named " + base + "; pick a different title");
        }
        return name;
    }
}
