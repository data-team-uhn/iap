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
package io.uhndata.iap.workflows.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared setup for the handlers that author workflow definitions and versions: a definition to build versions
 * under, activities configured the way the shipped system workflows configure them, and stand-ins for the things
 * the mock repository cannot express — a node the current user may read but not write, a model that will not
 * adapt, a diagram that breaks while it is read.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class AuthoringFixture
{
    /** The workflow definition every version in these tests belongs to. */
    static final String DEFINITION = "/Workflows/timeOff";

    /** Who the executions under test are acting for. */
    static final String ACTOR = "admin";

    /** The diagram the fixture's versions carry. */
    static final String BPMN = "<bpmn:definitions id=\"one\"/>";

    /** Where the activities these tests configure are built. */
    private static final String ACTIVITIES = "/SystemWorkflows/underTest/v1";

    private AuthoringFixture()
    {
    }

    /**
     * Registers the workflow models and creates the definition versions are built under.
     *
     * @param context the Sling context to set up
     */
    static void setUp(final SlingContext context)
    {
        WorkflowFixture.setUp(context);
        context.create().resource("/Workflows", WorkflowFixture.TYPE, "wf/WorkflowsHomepage");
        context.create().resource(DEFINITION, Map.of(
            WorkflowFixture.TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Time off request"));
    }

    /**
     * The repository path of a version of the fixture's definition.
     *
     * @param name the version's node name
     * @return a path
     */
    static String path(final String name)
    {
        return DEFINITION + "/" + name;
    }

    /**
     * Creates a version of the fixture's definition.
     *
     * @param context the Sling context to build in
     * @param name the node name
     * @param label the version label
     * @param state the lifecycle state
     * @param extra any further properties to set
     * @return the created version
     */
    static Resource createVersion(final SlingContext context, final String name, final String label,
        final WorkflowVersion.State state, final Map<String, Object> extra)
    {
        final Map<String, Object> properties = new HashMap<>(extra);
        properties.put(WorkflowFixture.TYPE, WorkflowVersion.RESOURCE_TYPE);
        properties.put("version", label);
        properties.put(WorkflowFixture.STATE, state.name());
        return context.create().resource(path(name), properties);
    }

    /**
     * Gives a version a BPMN source, the way an upload does: as an {@code nt:file} child.
     *
     * @param context the Sling context to build in
     * @param name the version's node name
     */
    static void loadDiagram(final SlingContext context, final String name)
    {
        context.load().binaryFile(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)),
            path(name) + "/bpmn.xml", "application/xml");
    }

    /**
     * An activity configured the way a system workflow's service task is, for a handler to read itself out of.
     *
     * @param context the Sling context to build in
     * @param name the activity's node name, unique within a test
     * @param configuration the properties the handler reads
     * @return the activity model
     */
    static Activity activity(final SlingContext context, final String name,
        final Map<String, Object> configuration)
    {
        final Map<String, Object> properties = new HashMap<>(configuration);
        properties.put(WorkflowFixture.TYPE, Activity.RESOURCE_TYPE);
        properties.put("elementId", name);
        final Resource resource = context.create().resource(ACTIVITIES + "/" + name, properties);
        final Activity activity = resource.adaptTo(Activity.class);
        assertNotNull(activity);
        return activity;
    }

    /**
     * Assembles a task context for a handler under test.
     *
     * @param target the resource the event is aimed at
     * @param event the event name
     * @param payload what it carries
     * @param activity the activity being performed
     * @param variables where the handler reports its results
     * @return the assembled context
     */
    static WorkflowTaskContextImpl context(final Resource target, final String event,
        final Map<String, Object> payload, final Activity activity, final Map<String, Object> variables)
    {
        return new WorkflowTaskContextImpl(target, new WorkflowEvent(event, payload), activity, variables, ACTOR);
    }

    /**
     * An uploaded document, the way the event servlet hands one to a handler.
     *
     * @param content what the file says
     * @param mimeType the type the caller declared, possibly {@code null}
     * @return an attachment over that content
     */
    static EventAttachment upload(final String content, final String mimeType)
    {
        return new EventAttachment()
        {
            @Override
            public String getFileName()
            {
                return "bpmn.xml";
            }

            @Override
            public String getMimeType()
            {
                return mimeType;
            }

            @Override
            public InputStream openStream()
            {
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /**
     * An upload that cannot be read, standing in for a transfer that broke halfway.
     *
     * @return an attachment whose stream throws
     */
    static EventAttachment brokenUpload()
    {
        return new EventAttachment()
        {
            @Override
            public String getFileName()
            {
                return "bpmn.xml";
            }

            @Override
            public String getMimeType()
            {
                return "application/xml";
            }

            @Override
            public InputStream openStream() throws IOException
            {
                throw new IOException("The upload broke");
            }
        };
    }

    /**
     * A version resource that no model will adapt, standing in for one whose model is unavailable.
     *
     * @param context the Sling context the version lives in
     * @param versionPath the path of the version to wrap
     * @return a resource that refuses to adapt to a {@link WorkflowVersion}
     */
    static Resource unreadable(final SlingContext context, final String versionPath)
    {
        final Resource resource = context.resourceResolver().getResource(versionPath);
        assertNotNull(resource);
        final Resource spy = Mockito.spy(resource);
        Mockito.doReturn(null).when(spy).adaptTo(WorkflowVersion.class);
        return spy;
    }

    /**
     * A version whose diagram fails partway through reading, standing in for a binary the repository cannot
     * deliver.
     *
     * @param context the Sling context the version lives in
     * @param versionPath the path of the version to wrap
     * @return a resource whose {@code bpmn.xml} child breaks when read
     */
    static Resource withUnreadableDiagram(final SlingContext context, final String versionPath)
    {
        final Resource resource = context.resourceResolver().getResource(versionPath);
        assertNotNull(resource);
        final Resource file = resource.getChild("bpmn.xml");
        assertNotNull(file);
        final Resource brokenFile = Mockito.spy(file);
        Mockito.doReturn(new InputStream()
        {
            @Override
            public int read()
            {
                return -1;
            }

            @Override
            public void close() throws IOException
            {
                throw new IOException("The stream broke");
            }
        }).when(brokenFile).adaptTo(InputStream.class);
        final Resource spy = Mockito.spy(resource);
        Mockito.doReturn(brokenFile).when(spy).getChild("bpmn.xml");
        return spy;
    }

    /**
     * Reads a diagram file the way a caller of {@link WorkflowVersion#getBpmnFile} would.
     *
     * @param file the file resource to read
     * @return its contents, decoded as UTF-8
     * @throws IOException if reading fails, which fails the test
     */
    static String read(final Resource file) throws IOException
    {
        assertNotNull(file);
        try (InputStream source = file.adaptTo(InputStream.class)) {
            assertNotNull(source);
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(source.readAllBytes())).toString();
        }
    }
}
