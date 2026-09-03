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
import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that raises a new submission: what the bootstrap workflow on {@code /Submissions} performs.
 * The event's {@code title} names the submission, and its {@code schemaVersion} — the <em>path</em> of a
 * {@code sch:SchemaVersion} — says what is being submitted against; the created submission holds a real
 * reference to it, and starts out tagged {@code draft}.
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

    /** The property naming the schema that version belongs to, written here rather than asked of the caller. */
    private static final String SCHEMA = "schema";

    /** The lifecycle a submission begins in, which nothing else would put it in. */
    private static final String DRAFT = "draft";

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
        final Object title = context.getEvent().get(TITLE);
        if (!(title instanceof String) || ((String) title).isBlank()) {
            throw new InvalidPayloadException("A title is required");
        }
        final Resource version = resolveSchemaVersion(context);
        // A UUID rather than anything derived from the title: it is what makes the prefix tree spread evenly, and
        // it means a submission's identity never depends on what it was called, so renaming one stays a rename
        final String name = UUID.randomUUID().toString();
        // The lifecycle state is a tag rather than a property, so nothing autocreates the starting one and the
        // handler that raises the submission is what puts it there. Written directly because this module ships the
        // definition it names, so there is no vocabulary to check it against that could disagree.
        final Resource created = context.getResourceResolver().create(bucketFor(context, name),
            name, Map.of("jcr:primaryType", "sub:Submission", TITLE, title, "tags", new String[] {DRAFT}));
        reference(created, version);
        context.setVariable(WorkflowResult.CREATED_PATH, created.getPath());
    }

    /**
     * Where a submission goes: a bucket in the {@link PrefixTree prefix tree} under {@code /Submissions}, rather
     * than the homepage itself.
     *
     * <p>Nothing bounds how many submissions an institution files, and a parent with a million children is slow to
     * write and slower to browse. Spreading them costs nothing to read, because they are found by query — the
     * listing endpoint scopes on {@code isdescendantnode}, so where in the tree a submission sits never has to be
     * known — and the name is a UUID precisely so the spread is even, which a title-derived name would not be
     * (every submission raised in a given week would land in the same handful of buckets).</p>
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
     * Points the fresh submission at both the schema version it answers and the schema that version belongs to,
     * with real {@code REFERENCE} properties. This has to go through the JCR API: a plain string property would
     * carry the right identifier but the wrong type, and the strict {@code sub:Submission} definition rejects it
     * at commit.
     *
     * <p>Both, because nobody should have to state the schema separately when the version already implies it —
     * and because a query for everything submitted against a schema is otherwise a join.</p>
     *
     * @param created the submission just created
     * @param version the vetted schema version
     * @throws PersistenceException when the repository refuses either reference
     */
    private void reference(final Resource created, final Resource version) throws PersistenceException
    {
        final Node submission = node(created, "A freshly created submission");
        final Node versionNode = node(version, "A vetted schema version");
        // The schema is the version's parent, which is what SchemaVersion.getSchema() reads; resolveSchemaVersion
        // has already established that it is there and active
        final Node schemaNode = node(Objects.requireNonNull(version.getParent(),
            "A vetted schema version always sits inside its schema"), "A schema");
        try {
            submission.setProperty(SCHEMA_VERSION, versionNode);
            submission.setProperty(SCHEMA, schemaNode);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the schema", e);
        }
    }

    /**
     * The JCR node behind a resource.
     *
     * @param resource the resource to unwrap
     * @param what what it is, for the message when it is somehow not node-backed
     * @return the node
     */
    private static Node node(final Resource resource, final String what)
    {
        return Objects.requireNonNull(resource.adaptTo(Node.class), what + " is always backed by a JCR node");
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

}
