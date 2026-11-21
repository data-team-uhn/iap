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

import java.util.Map;
import java.util.Objects;

import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.internal.handlers.CreateEntityHandler;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.models.WorkflowsHomepage;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TAGS;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.tags;

/**
 * Shared setup for the engine tests: the {@code /Workflows} homepage events are aimed at, the users asking, builders
 * for system workflow definitions of various shapes under {@code /SystemWorkflows}, and the ways a test changes the
 * engine {@link io.uhndata.iap.workflows.testing.WorkflowPlugins#ENGINE the plugin} installed. Public for the
 * handlers' tests, which live in a package of their own and run each handler in a {@link #taskContext task context}
 * built here, the engine's own being private to it. The engine's sessions over the mock repository are
 * {@link ServiceSessions}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class EngineFixture
{
    /** The path of the bootstrap system workflow the tests build. */
    public static final String WORKFLOW = SystemWorkflowsHomepage.HOMEPAGE_PATH + "/createWorkflow";

    /** The path of the version node of the bootstrap system workflow. */
    public static final String VERSION = WORKFLOW + "/v1";

    /** An administrator, who passes every performer check. */
    static final String ADMIN = "admin";

    /** An ordinary user belonging to {@link #REQUESTERS}. */
    static final String REQUESTER = "demo-requester";

    /** The group {@link #REQUESTER} belongs to. */
    static final String REQUESTERS = "time-off-requesters";

    /** Ranks a test's stand-in above whatever provides the same service. */
    private static final int STAND_IN_RANKING = Integer.MAX_VALUE;

    /** Ranks a component built again above the one the engine plugin registered. */
    private static final int REBUILT_RANKING = 1;

    private EngineFixture()
    {
    }

    /**
     * A resolver reporting the given user, both the way Sling answers and the way the repository does.
     *
     * <p>Masking {@link ResourceResolver#getUserID()} alone is not enough, and the difference is the point. The
     * engine takes the user id from the JCR session, because Sling's answer is the name as typed at login while
     * the repository's is the canonical one. A fixture faking only Sling's answer would be faking the value
     * production deliberately does not read.</p>
     *
     * <p>Where the context has no JCR session at all, there is nothing to mask and Sling's answer is all there
     * is, which is the fallback production takes.</p>
     *
     * @param delegate the resolver to wrap
     * @param actor the user id it should report
     * @return the wrapped resolver
     */
    static ResourceResolver actingAs(final ResourceResolver delegate, final String actor)
    {
        final Session real = delegate.adaptTo(Session.class);
        final Session masked;
        if (real == null) {
            masked = null;
        } else {
            // Delegating rather than spying: the mock session is stateful, and everything the engine does with it
            // has to reach the real one
            masked = Mockito.mock(Session.class, AdditionalAnswers.delegatesTo(real));
            Mockito.doReturn(actor).when(masked).getUserID();
        }
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public String getUserID()
            {
                return actor;
            }

            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return masked != null && type == Session.class ? type.cast(masked) : super.adaptTo(type);
            }
        };
    }

    /**
     * The same target, seen through a resolver that reports the user's name as they typed it at login rather than
     * as the repository resolved it. A login resolves case-insensitively, so the two can differ.
     *
     * @param target a target whose session is already masked with the canonical id, as {@link #actingAs} does
     * @param spelling what Sling should report the user id to be
     * @return the target, whose resolver reports {@code spelling} as the user id
     */
    static Resource typedAtLogin(final Resource target, final String spelling)
    {
        return seenThrough(target, new ResourceResolverWrapper(target.getResourceResolver())
        {
            @Override
            public String getUserID()
            {
                return spelling;
            }
        });
    }

    /**
     * A resource as the given resolver sees it.
     *
     * @param resource the resource
     * @param resolver the resolver it should report
     * @return the same resource, reporting that resolver as its own
     */
    static Resource seenThrough(final Resource resource, final ResourceResolver resolver)
    {
        return new ResourceWrapper(resource)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }

    /**
     * A context for a handler under test to execute in, as the engine builds one for a service task: aimed at a
     * target, for an event, performing an activity, sharing the given variables, and refusing to send any event on.
     *
     * @param context a Sling context with the engine installed
     * @param target the resource the event was aimed at
     * @param event the triggering event
     * @param activity the activity being performed
     * @param variables the execution's variables, where the handler reports what it did
     * @param actor the user the execution is acting for
     * @return the context
     */
    public static WorkflowTaskContext taskContext(final SlingContext context, final Resource target,
        final WorkflowEvent event, final Activity activity, final Map<String, Object> variables, final String actor)
    {
        final ServiceTaskDispatcher noFurtherTasks = context.getService(ServiceTaskDispatchers.class)
            .create((to, sent, sender, depth) -> {
                throw new IllegalStateException("No event was expected to be sent here");
            });
        return new WorkflowTaskContextImpl(target, event, activity, variables, actor, noFurtherTasks, 0);
    }

    /**
     * Ranks a test's stand-in for one of the engine's services above the one the engine plugin registered. A
     * component registered from then on binds the stand-in; one registered before keeps what it has, so the test
     * {@link #rebuilt rebuilds} the components it needs to see the stand-in.
     *
     * @param <T> the service type
     * @param context the Sling context the engine is installed in
     * @param service the service the stand-in provides
     * @param standIn the stand-in
     * @return the stand-in
     */
    static <T> T standIn(final SlingContext context, final Class<T> service, final T standIn)
    {
        return context.registerService(service, standIn, Constants.SERVICE_RANKING, STAND_IN_RANKING);
    }

    /**
     * Registers one of the engine's components again, ranked above the one the engine plugin registered and below
     * any {@link #standIn stand-in}, so that it binds the stand-ins registered since.
     *
     * @param <T> the component type
     * @param context the Sling context the engine is installed in
     * @param component the component to build again
     * @return the new component
     */
    static <T> T rebuilt(final SlingContext context, final Class<T> component)
    {
        return context.registerInjectActivateService(component, Constants.SERVICE_RANKING, REBUILT_RANKING);
    }

    /**
     * Creates the {@code /Workflows} homepage the tests aim their events at, posted to by an administrator.
     *
     * @param context the Sling context to build in
     * @return the homepage resource
     */
    public static Resource createTarget(final SlingContext context)
    {
        return createTarget(context, ADMIN);
    }

    /**
     * Creates the {@code /Workflows} homepage the tests aim their events at, posted to by the given user. The
     * engine takes the actor from the session that resolved the target, so that is where a test says who is
     * asking.
     *
     * @param context the Sling context to build in
     * @param actor the user id the target's session reports
     * @return the homepage resource
     */
    public static Resource createTarget(final SlingContext context, final String actor)
    {
        final Resource homepage = context.create().resource("/Workflows", TYPE, WorkflowsHomepage.RESOURCE_TYPE);
        return seenThrough(homepage, actingAs(homepage.getResourceResolver(), actor));
    }

    /**
     * The properties of a node, to change what a definition or an instance carries.
     *
     * @param context the Sling context holding the node
     * @param path the node
     * @return its modifiable properties
     */
    static ModifiableValueMap modify(final SlingContext context, final String path)
    {
        return Objects.requireNonNull(context.resourceResolver().getResource(path).adaptTo(ModifiableValueMap.class),
            "The mock repository lets any node be modified");
    }

    /**
     * Creates {@code /SystemWorkflows} holding one definition with one {@link WorkflowVersion#isActive() active}
     * version targeting {@code wf/WorkflowsHomepage} — without any flow nodes yet.
     *
     * @param context the Sling context to build in
     * @param targetResourceType the resource type the version declares itself for, or {@code null} for none
     */
    public static void createSystemWorkflow(final SlingContext context, final String targetResourceType)
    {
        createSystemWorkflow(context, WorkflowVersion.ACTIVE_TAG, targetResourceType);
    }

    /**
     * Creates {@code /SystemWorkflows} holding one definition with one version carrying the given lifecycle tag,
     * targeting {@code wf/WorkflowsHomepage}, without any flow nodes yet. Only the version's tag is settable here,
     * since a definition accepts instantiation exactly when one of its versions is
     * {@link WorkflowVersion#isActive() active}.
     *
     * @param context the Sling context to build in
     * @param lifecycle the lifecycle tag the version carries
     * @param targetResourceType the resource type the version declares itself for, or {@code null} for none
     */
    public static void createSystemWorkflow(final SlingContext context, final String lifecycle,
        final String targetResourceType)
    {
        context.create().resource(SystemWorkflowsHomepage.HOMEPAGE_PATH, TYPE, SystemWorkflowsHomepage.RESOURCE_TYPE);
        context.create().resource(WORKFLOW, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Create a workflow"));
        if (targetResourceType == null) {
            context.create().resource(VERSION, Map.of(
                TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(lifecycle)));
        } else {
            context.create().resource(VERSION, Map.of(
                TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(lifecycle),
                "targetResourceType", targetResourceType));
        }
    }

    /**
     * Adds the straight-through bootstrap graph to the version created by
     * {@link #createSystemWorkflow}: a {@code create}-catching start event, a {@code createEntity} service task
     * configured to create workflow definitions, and an end event.
     *
     * @param context the Sling context to build in
     * @param performers the principals the start event admits; none means it admits nobody but administrators
     */
    public static void createBootstrapGraph(final SlingContext context, final String... performers)
    {
        context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "requested", "messageName", "create",
            "performers", performers));
        context.create().resource(VERSION + "/requested/toCreate", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "toCreate", "targetRef", "create"));
        context.create().resource(VERSION + "/create", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "create",
            "handler", CreateEntityHandler.HANDLER_NAME, "entityType", "wf:WorkflowDefinition"));
        context.create().resource(VERSION + "/create/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "toDone", "targetRef", "done"));
        context.create().resource(VERSION + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "done"));
    }
}
