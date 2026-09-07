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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreateSubmissionHandler}: vetting the schema version a submission answers, and raising
 * the submission itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreateSubmissionHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String DRAFT = "draft";

    // JCR-backed rather than the plain mock: the handler sets a real REFERENCE through the JCR API
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final CreateSubmissionHandler handler = new CreateSubmissionHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Schema.class, SchemaVersion.class,
            Activity.class);
        this.target = this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage");
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateSubmissionHandler.NAME, this.handler.getName());
    }

    @Test
    void raisesADraftSubmissionAgainstTheSchemaVersion() throws Exception
    {
        final WorkflowTaskContext taskContext = context(Map.of(
            "title", "My day off", "schemaVersion", VERSION_PATH));

        this.handler.execute(taskContext);

        final String path = (String) taskContext.getVariable(WorkflowResult.CREATED_PATH);
        final Resource created = this.context.resourceResolver().getResource(path);
        assertNotNull(created);
        assertEquals("sub:Submission", created.getValueMap().get("jcr:primaryType"));
        assertEquals("My day off", created.getValueMap().get("title"));
        assertEquals(List.of(DRAFT), List.of(created.getValueMap().get("tags", new String[0])));
        // Real REFERENCEs, holding the version's and the schema's own identifiers
        final javax.jcr.Node versionNode =
            this.context.resourceResolver().getResource(VERSION_PATH).adaptTo(javax.jcr.Node.class);
        assertEquals(versionNode.getIdentifier(),
            created.getValueMap().get("schemaVersion", String.class));
        // Both, so that "everything submitted against this schema" is one comparison rather than a join — and
        // so that nobody raising a submission has to state a fact the version already implies
        final javax.jcr.Node schemaNode = this.context.resourceResolver()
            .getResource("/Schemas/timeOffRequest").adaptTo(javax.jcr.Node.class);
        assertEquals(schemaNode.getIdentifier(), created.getValueMap().get("schema", String.class));
    }

    @Test
    void namesTheSubmissionByUuidAndFilesItInThePrefixTree() throws Exception
    {
        final WorkflowTaskContext taskContext = context(Map.of(
            "title", "My day off", "schemaVersion", VERSION_PATH));

        this.handler.execute(taskContext);

        // The name is a UUID, and the path is the one the prefix tree computes for it — asserted through
        // PrefixTree rather than by spelling the layout out here, since the layout is its business
        final String path = (String) taskContext.getVariable(WorkflowResult.CREATED_PATH);
        final String name = path.substring(path.lastIndexOf('/') + 1);
        assertEquals(UUID.fromString(name).toString(), name);
        assertEquals(PrefixTree.pathFor("/Submissions", name), path);
        // The buckets are folders, which is what lets the homepage's own read grant name their type
        assertEquals("sling:Folder",
            this.context.resourceResolver().getResource(path).getParent().getValueMap().get("jcr:primaryType"));
    }

    @Test
    void raisesTwoSubmissionsWithTheSameTitleWithoutComplaint() throws Exception
    {
        // What replaced the old name-collision handling: a title is a label, not an identity, so two requests
        // for the same thing are two requests rather than a naming problem to report to the submitter
        final WorkflowTaskContext first = context(Map.of("title", "Busy", "schemaVersion", VERSION_PATH));
        final WorkflowTaskContext second = context(Map.of("title", "Busy", "schemaVersion", VERSION_PATH));

        this.handler.execute(first);
        this.handler.execute(second);

        assertNotEquals(first.getVariable(WorkflowResult.CREATED_PATH),
            second.getVariable(WorkflowResult.CREATED_PATH));
    }

    @Test
    void translatesAFailedBucketIntoAPersistenceFailure()
    {
        // The homepage's own JCR node fails on any use, so the prefix tree's buckets cannot be opened. Like the
        // reference failure below, that has to reach the engine as a persistence problem it knows how to translate
        // rather than as a raw repository error escaping a handler.
        final javax.jcr.Node explosive = Mockito.mock(javax.jcr.Node.class, invocation -> {
            throw new javax.jcr.RepositoryException("boom");
        });
        this.target = new ResourceWrapper(this.target)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == javax.jcr.Node.class ? type.cast(explosive) : super.adaptTo(type);
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context(Map.of("title", "My day off", "schemaVersion", VERSION_PATH))));
        assertTrue(failure.getMessage().contains("Could not open the bucket"));
    }

    @Test
    void translatesAFailedReferenceIntoAPersistenceFailure()
    {
        // A schema version whose JCR node fails on any use: the reference cannot be written, and the failure
        // must surface as a persistence problem for the engine to translate, not as a raw repository error
        final javax.jcr.Node explosive = Mockito.mock(javax.jcr.Node.class, invocation -> {
            throw new javax.jcr.RepositoryException("boom");
        });
        final ResourceResolver resolver = this.context.resourceResolver();
        final ResourceResolver sabotaged = new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource real = super.getResource(path);
                if (real == null || !VERSION_PATH.equals(path)) {
                    return real;
                }
                return new ResourceWrapper(real)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == javax.jcr.Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context(Map.of(
                "title", "My day off", "schemaVersion", VERSION_PATH), sabotaged)));
        assertTrue(failure.getMessage().contains("Could not reference"));
    }

    @Test
    void requiresATitle()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("schemaVersion", VERSION_PATH))));
        assertTrue(rejection.getMessage().contains("title is required"));
    }

    @Test
    void acceptsATitleThatCouldNotBeANodeName() throws Exception
    {
        // Nothing is derived from the title any more, so it no longer has to yield a usable name. That is not
        // just a relaxed rule: a title in a script with no Latin letters used to be refused outright.
        final WorkflowTaskContext taskContext = context(Map.of("title", "???", "schemaVersion", VERSION_PATH));

        this.handler.execute(taskContext);

        assertNotNull(taskContext.getVariable(WorkflowResult.CREATED_PATH));
    }

    @Test
    void requiresASchemaVersion()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("title", "My day off"))));
        assertTrue(rejection.getMessage().contains("schemaVersion is required"));
    }

    @Test
    void refusesAPathWithNothingAtIt()
    {
        // Note that this is not a visibility check: the handler works through the engine's privileged session, so
        // "there is nothing there" means exactly that, and who may submit was settled before it ran
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(
                "title", "My day off", "schemaVersion", "/Schemas/nowhere/v1"))));
        assertTrue(rejection.getMessage().contains("no schema version at"));
    }

    @Test
    void refusesAPathToSomethingElse()
    {
        // adaptTo is not a type filter, so the handler must check the resource type itself
        this.context.create().resource("/Schemas/stray", TYPE, "nt:unstructured");

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of(
            "title", "My day off", "schemaVersion", "/Schemas/stray"))));
    }

    @Test
    void refusesAnInactiveSchemaVersion()
    {
        this.context.create().resource("/Schemas/timeOffRequest/old", Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "0.9", "active", false));

        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(
                "title", "My day off", "schemaVersion", "/Schemas/timeOffRequest/old"))));
        assertTrue(rejection.getMessage().contains("not accepting new submissions"));
    }

    @Test
    void refusesAVersionOfAnInactiveSchema()
    {
        // The whole schema was retired: even its still-flagged-active versions accept nothing
        this.context.create().resource("/Schemas/retired", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Retired", "active", false));
        this.context.create().resource("/Schemas/retired/v1", Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of(
            "title", "My day off", "schemaVersion", "/Schemas/retired/v1"))));
    }

    @Test
    void refusesAVersionStoredOutsideASchema()
    {
        this.context.create().resource("/loose", Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of(
            "title", "My day off", "schemaVersion", "/loose"))));
    }

    /**
     * Builds a task context whose event carries the given payload, aimed at {@code /Submissions}.
     *
     * @param payload the event payload
     * @return the assembled context
     */
    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        return context(payload, this.context.resourceResolver());
    }

    /**
     * Builds a task context whose event carries the given payload, working through the given session.
     *
     * @param payload the event payload
     * @param resolver the session the handler works through
     * @return the assembled context
     */
    private WorkflowTaskContext context(final Map<String, Object> payload, final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent("create", payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        final Resource submissionsHomepage = this.target;
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return submissionsHomepage;
            }

            @Override
            public String getActor()
            {
                return "demo-requester";
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return event;
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public Object getVariable(final String name)
            {
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }
}
