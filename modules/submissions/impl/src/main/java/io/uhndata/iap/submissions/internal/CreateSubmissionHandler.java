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
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.PrefixTree;
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

    /** The property naming the schema that version belongs to, written here rather than asked of the caller. */
    private static final String SCHEMA_PROPERTY = "schema";

    /**
     * The type of the prefix tree's buckets. A plain folder: they hold no data of their own, and being a type the
     * homepage's own read grant names means a submitter can reach what they filed without the buckets having to be
     * granted one by one.
     */
    private static final String BUCKET_TYPE = "sling:Folder";

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
        // A UUID rather than anything derived from the title: it is what makes the prefix tree spread evenly, and
        // it means a submission's identity never depends on what it was called, so renaming one stays a rename
        final String name = UUID.randomUUID().toString();
        final Resource submission = context.getResourceResolver().create(bucketFor(context, name),
            name, Map.of("jcr:primaryType", "sub:Submission", TITLE_PROPERTY, title));
        setSchemaVersion(submission, schemaVersion);
        draft(submission);
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, submission.getPath());
    }

    /**
     * Where a submission goes: a bucket in the {@link PrefixTree prefix tree} under {@code /Submissions}, rather
     * than the homepage itself.
     *
     * <p>Nothing bounds how many submissions an institution files, and a parent with a million children is slow to
     * write and slower to browse. Spreading them costs nothing to read, because they are found by query. The
     * listing endpoint scopes on {@code isdescendantnode}, so where in the tree a submission sits never has to be
     * known, and the name is a UUID so the spread is even.</p>
     *
     * @param context the executing task's context, whose target is the homepage
     * @param name the name the submission will be created under
     * @return the resource to create the submission in
     * @throws PersistenceException when the buckets cannot be opened
     */
    private Resource bucketFor(final WorkflowTaskContext context, final String name) throws PersistenceException
    {
        final Node homepage = Objects.requireNonNull(context.getTarget().adaptTo(Node.class),
            "The submissions homepage is always backed by a JCR node");
        try {
            final Node bucket = PrefixTree.bucketFor(homepage, name, BUCKET_TYPE);
            return Objects.requireNonNull(context.getResourceResolver().getResource(bucket.getPath()),
                "A bucket that was just opened is readable by the session that opened it");
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not open the bucket for the new submission", e);
        }
    }

    /**
     * Places the {@code draft} lifecycle tag on the submission just raised.
     *
     * <p>Here rather than on the workflow's end event, which is where a lifecycle is normally declared: this
     * definition targets the submissions homepage, so the host an end event would tag is {@code /Submissions}
     * and not the submission. A submission without the tag cannot be answered at all, since that is the state
     * saving checks for.</p>
     *
     * @param submission the submission just created
     * @throws PersistenceException when the tag cannot be written
     */
    private void draft(final Resource submission) throws PersistenceException
    {
        // Every resource adapts to Taggable where the tags bundle is installed, and it starts first
        Objects.requireNonNull(submission.adaptTo(Taggable.class), "A submission is taggable")
            .tag(Submission.DRAFT_TAG);
    }

    /**
     * Points the submission at its schema version with a real {@code REFERENCE}. This must go through the JCR API,
     * since the Sling API does not support {@code REFERENCE} properties. A plain string property would carry the right
     * identifier but the wrong type, and the strict {@code sub:Submission} definition rejects it at commit.
     *
     * <p>The schema is written as well as the version, because nobody should have to state the schema separately
     * when the version already implies it, and because a query for everything submitted against a schema is
     * otherwise a join.</p>
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
        // The schema is the version's parent, which is what SchemaVersion.getSchema() reads; resolveSchemaVersion
        // has already established that it is there and active
        final Node schema = Objects.requireNonNull(
            Objects.requireNonNull(schemaVersion.getParent(),
                "A vetted schema version always sits inside its schema").adaptTo(Node.class),
            "A schema is always backed by a JCR node");
        try {
            node.setProperty(SCHEMA_VERSION_PROPERTY, target);
            node.setProperty(SCHEMA_PROPERTY, schema);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the schema", e);
        }
    }

    /**
     * Resolves and vets the schema version the payload points at. It must exist, be a schema version rather
     * than whatever else sits at that path, and both it and its parent schema must be active. That last one
     * is where "no new submissions may be created from an inactive version" is actually enforced.
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
}
