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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that raises a new submission: what the bootstrap workflow on {@code /Submissions} performs.
 * The event's {@code title} labels the submission, and its {@code schemaVersion}, the <em>path</em> of a
 * {@code sch:SchemaVersion}, says what is being submitted against. The submission is named by a UUID and filed
 * in the homepage's {@link PrefixTree prefix tree}. It holds real references to the schema version and to the
 * schema that version belongs to.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateSubmissionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "createSubmission";

    /** The payload entry holding the submission's title. */
    private static final String TITLE_PARAMETER = "title";

    /** The payload entry pointing at the schema version being submitted against. */
    private static final String SCHEMA_VERSION_PARAMETER = "schemaVersion";

    /** Where the title is kept on the submission. Spelled like the payload entry, but not the same name. */
    private static final String TITLE_PROPERTY = "title";

    /** The {@code REFERENCE} property holding the schema version. */
    private static final String SCHEMA_VERSION_PROPERTY = "schemaVersion";

    /** The {@code REFERENCE} property holding the schema that version belongs to. */
    private static final String SCHEMA_PROPERTY = "schema";

    /** The lifecycle tag of a schema version that accepts new submissions. */
    private static final String ACTIVE_TAG = "active";

    /** The lifecycle tag of a closed schema, which its versions inherit. */
    private static final String RETIRED_TAG = "retired";

    /** The node type of the prefix tree's buckets, which hold no data of their own. */
    private static final String BUCKET_TYPE = "sling:Folder";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final String title = Payloads.requireText(context.getEvent(), TITLE_PARAMETER, "A title is required");
        final OpenVersion schemaVersion = resolveSchemaVersion(context);
        // A UUID spreads the prefix tree evenly, and leaves the title a label rather than an identity
        final String name = UUID.randomUUID().toString();
        final Resource submission = context.getResourceResolver().create(
            PrefixTree.pendingBucketFor(context.getTarget(), name, BUCKET_TYPE), name,
            Map.of("jcr:primaryType", "sub:Submission", TITLE_PROPERTY, title));
        pointAt(submission, schemaVersion);
        draft(submission);
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, submission.getPath());
    }

    /**
     * Points the submission just raised at the schema version it answers, and at the schema that version belongs to.
     *
     * @param submission the submission just created
     * @param schemaVersion the vetted schema version it was raised against
     * @throws PersistenceException when a reference cannot be written
     */
    private void pointAt(final Resource submission, final OpenVersion schemaVersion) throws PersistenceException
    {
        ReferenceUtils.setReference(submission, SCHEMA_VERSION_PROPERTY, schemaVersion.version());
        ReferenceUtils.setReference(submission, SCHEMA_PROPERTY, schemaVersion.schema());
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
     * Resolves and vets the schema version the payload points at. It must exist, be a schema version rather
     * than whatever else sits at that path, carry the {@code active} lifecycle tag, and belong to a schema that
     * is not {@code retired}: a retired schema closes all of its versions, which inherit the tag rather than
     * carry it. This is where "no new submissions against a closed version" is actually enforced.
     *
     * @param context the executing task's context
     * @return the resolved schema version, and the schema it belongs to
     * @throws InvalidPayloadException when the payload does not point at an open schema version
     */
    private OpenVersion resolveSchemaVersion(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final String path = Payloads.requireText(context.getEvent(), SCHEMA_VERSION_PARAMETER,
            "A schemaVersion is required");
        final Resource resource = context.getResourceResolver().getResource(path);
        if (resource == null || !resource.isResourceType(SchemaVersion.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("There is no schema version at " + path);
        }
        final SchemaVersion version = Objects.requireNonNull(resource.adaptTo(SchemaVersion.class),
            "A sch:SchemaVersion resource failed to adapt to its model");
        final Schema schema = version.getSchema();
        if (schema == null || !isOpen(version, schema)) {
            throw new InvalidPayloadException(
                "The schema version at " + path + " is not accepting new submissions");
        }
        return new OpenVersion(resource, Objects.requireNonNull(
            context.getResourceResolver().getResource(schema.getPath()),
            "A schema is readable wherever its version is"));
    }

    /**
     * Whether a schema version takes new submissions: it carries {@code active}, and its schema is not
     * {@code retired}.
     *
     * @param version the schema version
     * @param schema the schema it belongs to
     * @return {@code true} if a submission may be raised against it
     */
    private static boolean isOpen(final SchemaVersion version, final Schema schema)
    {
        final Taggable versionTags = version.as(Taggable.class);
        final Taggable schemaTags = schema.as(Taggable.class);
        return versionTags != null && versionTags.hasOwnTag(ACTIVE_TAG)
            && schemaTags != null && !schemaTags.hasOwnTag(RETIRED_TAG);
    }

    /**
     * A schema version that takes new submissions, and the schema it belongs to.
     *
     * @param version the schema version
     * @param schema the schema it belongs to
     * @version $Id$
     * @since 0.1.0
     */
    private record OpenVersion(Resource version, Resource schema)
    {
    }
}
