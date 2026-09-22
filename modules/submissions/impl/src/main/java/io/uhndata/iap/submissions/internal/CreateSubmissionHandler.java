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
 * The event's {@code title} names the submission, and its {@code schemaVersion}, the <em>path</em> of a
 * {@code sch:SchemaVersion}, says what is being submitted against. The created submission holds a real
 * reference to it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateSubmissionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "createSubmission";

    /** The payload entry naming the submission to create. */
    private static final String TITLE_PARAMETER = "title";

    /** The payload entry pointing at the schema version being submitted against. */
    private static final String SCHEMA_VERSION_PARAMETER = "schemaVersion";

    /** Where the title is kept on the submission. Spelled like the payload entry, but not the same name. */
    private static final String TITLE_PROPERTY = "title";

    /** The {@code REFERENCE} property holding the schema version. */
    private static final String SCHEMA_VERSION_PROPERTY = "schemaVersion";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object title = context.getEvent().get(TITLE_PARAMETER);
        if (!(title instanceof String) || ((String) title).isBlank()) {
            throw new InvalidPayloadException("A title is required");
        }
        final Resource schemaVersion = resolveSchemaVersion(context);
        final Resource submission = context.getResourceResolver().create(context.getTarget(),
            freeName(context.getTarget(), (String) title),
            Map.of("jcr:primaryType", "sub:Submission", TITLE_PROPERTY, title));
        setSchemaVersion(submission, schemaVersion);
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, submission.getPath());
    }

    /**
     * Points the submission at its schema version with a real {@code REFERENCE}. This must go through the JCR API,
     * since the Sling API does not support {@code REFERENCE} properties. A plain string property would carry the right
     * identifier but the wrong type, and the strict {@code sub:Submission} definition rejects it at commit.
     *
     * @param submission the submission just created
     * @param schemaVersion the vetted schema version
     * @throws PersistenceException when the repository refuses the reference
     */
    private void setSchemaVersion(final Resource submission, final Resource schemaVersion)
        throws PersistenceException
    {
        final Node node = Objects.requireNonNull(submission.adaptTo(Node.class),
            "A freshly created submission is always backed by a JCR node");
        final Node target = Objects.requireNonNull(schemaVersion.adaptTo(Node.class),
            "A vetted schema version is always backed by a JCR node");
        try {
            node.setProperty(SCHEMA_VERSION_PROPERTY, target);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the schema version", e);
        }
    }

    /**
     * Resolves and vets the schema version the payload points at. It must exist, be a schema version rather
     * than whatever else sits at that path, and both it and its parent schema must be active. That last one
     * is where "no new submissions may be created from an inactive version" is actually enforced.
     *
     * @param context the executing task's context
     * @return the resolved schema version's resource
     * @throws InvalidPayloadException when the payload does not point at an active schema version
     */
    private Resource resolveSchemaVersion(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object path = context.getEvent().get(SCHEMA_VERSION_PARAMETER);
        if (!(path instanceof String) || ((String) path).isBlank()) {
            throw new InvalidPayloadException("A schemaVersion is required");
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
     * @throws InvalidPayloadException when the title yields no usable name
     */
    private String freeName(final Resource parent, final String title) throws InvalidPayloadException
    {
        final String base = NodeNameUtils.camelCase(title);
        if (base.isEmpty()) {
            throw new InvalidPayloadException("The title must contain at least one letter or digit");
        }
        // Always answers. Past a hundred siblings the suffix turns random rather than giving up: refusing
        // to record a submission that was raised is the worse outcome
        return NodeNameUtils.findFreeName(parent, base);
    }
}
