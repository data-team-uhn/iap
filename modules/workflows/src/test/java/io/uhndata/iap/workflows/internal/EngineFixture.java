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

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
import io.uhndata.iap.conditions.internal.TagsOperandResolver;
import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.internal.CreatorResolver;
import io.uhndata.iap.principals.internal.MeResolver;
import io.uhndata.iap.principals.internal.PrincipalServiceImpl;
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
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TAGS;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.tags;

/**
 * Shared setup for the engine tests: the {@code /Workflows} homepage events are aimed at, builders for system
 * workflow definitions of various shapes under {@code /SystemWorkflows}, and a stand-in for the user store that
 * the mock repository does not have but authorization cannot do without. Public for the handlers' tests, which
 * live in a package of their own and run each handler in a {@link #taskContext task context} built here, the
 * engine's own being private to it.
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

    /** Every principal granted read since the last fixture was built, for the access tests to assert on. */
    static final List<String> GRANTED = new ArrayList<>();

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
     * How a task context performs further work, for a handler under test that does none: no handlers to dispatch
     * to, and no event may be sent.
     *
     * @return a dispatcher refusing to send events
     */
    static ServiceTaskDispatcher noFurtherTasks()
    {
        try {
            return new ServiceTaskDispatcher(List.of(), (to, event, actor, depth) -> {
                throw new IllegalStateException("No event was expected to be sent here");
            }, routing(), runners());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("The fixture could not build its condition evaluator", e);
        }
    }

    /**
     * A context for a handler under test to execute in, as the engine builds one for a service task: aimed at a
     * target, for an event, performing an activity, sharing the given variables, and with no further tasks to
     * dispatch.
     *
     * @param target the resource the event was aimed at
     * @param event the triggering event
     * @param activity the activity being performed
     * @param variables the execution's variables, where the handler reports what it did
     * @param actor the user the execution is acting for
     * @return the context
     */
    public static WorkflowTaskContext taskContext(final Resource target, final WorkflowEvent event,
        final Activity activity, final Map<String, Object> variables, final String actor)
    {
        return new WorkflowTaskContextImpl(target, event, activity, variables, Actor.of(actor), noFurtherTasks(), 0);
    }

    /**
     * The principal service, wired as the platform wires it: the real service, with the special names it ships.
     * Built by hand for the same reason as {@link #conditions()}.
     *
     * @return a principal service answering {@code @creator} and {@code @me}
     */
    static PrincipalService principals()
    {
        final PrincipalServiceImpl service = new PrincipalServiceImpl();
        try {
            final Field resolvers = PrincipalServiceImpl.class.getDeclaredField("resolvers");
            resolvers.setAccessible(true);
            resolvers.set(service, List.of(new CreatorResolver(), new MeResolver()));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("The fixture could not build its principal service", e);
        }
        return service;
    }

    /**
     * A history store that keeps what it is told, so that a test can see what an event recorded. Each action is
     * filed under its position, so the first is {@code /History/1}.
     */
    static final class History implements HistoryRecorder
    {
        private final List<RecordedAction> actions = new ArrayList<>();

        /**
         * What has been recorded so far.
         *
         * @return the actions, in the order they were recorded
         */
        List<RecordedAction> actions()
        {
            return this.actions;
        }

        @Override
        public String record(final Session session, final RecordedAction action)
        {
            this.actions.add(action);
            return "/History/" + this.actions.size();
        }

        @Override
        public void completeSnapshots(final Session session, final String actionPath,
            final Map<String, String> snapshots)
        {
            throw new UnsupportedOperationException("The engine takes no snapshots");
        }
    }

    /**
     * Who may act for others, as the engine is wired with it.
     *
     * @param delegates the principal name of the group whose members may
     * @return a delegation policy
     */
    static Delegation delegation(final String delegates)
    {
        final DelegationConfiguration config = Mockito.mock(DelegationConfiguration.class);
        Mockito.when(config.delegates()).thenReturn(delegates);
        return new Delegation(performers(), config);
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
     * A resolver factory handing out the engine's service session, wrapped so that it can be asked who the
     * repository's users are. The mock repository has no user store at all, so without this every event would
     * fail as "the repository cannot be asked who its users are" rather than exercising the check.
     *
     * @param context the Sling context whose real factory does the work
     * @param failure what the session's commit should throw, or {@code null} for a session that commits normally
     * @return a factory to inject into the engine
     */
    static ResourceResolverFactory serviceUsers(final SlingContext context, final PersistenceException failure)
    {
        GRANTED.clear();
        final ResourceResolverFactory real = context.getService(ResourceResolverFactory.class);
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        try {
            Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenAnswer(invocation -> {
                final ResourceResolver delegate = real.getServiceResourceResolver(invocation.getArgument(0));
                // Delegating rather than replacing. Everything the runtime does with the session, resolving
                // references by identifier and reading nodes, must still be the mock repository's behaviour,
                // with only the two things it has no notion of supplied on top
                return new ResourceResolverWrapper(withRepositoryServices(delegate))
                {
                    @Override
                    public Resource create(final Resource parent, final String name,
                        final Map<String, Object> properties) throws PersistenceException
                    {
                        return super.create(parent, name, autocreatedResourceType(properties));
                    }

                    @Override
                    public void commit() throws PersistenceException
                    {
                        if (failure != null) {
                            throw failure;
                        }
                        super.commit();
                    }

                    @Override
                    public void revert()
                    {
                        // The mock session cannot refresh, so rolling back is beyond it. That the engine reverts
                        // a failed run is asserted against a real repository by the integration tests; here the
                        // point is only that a failure surfaces as the right exception rather than as this one.
                        try {
                            super.revert();
                        } catch (final UnsupportedOperationException e) {
                            // Nothing this repository can do
                        }
                    }
                };
            });
        } catch (final LoginException e) {
            throw new IllegalStateException(e);
        }
        return factory;
    }

    /**
     * Builds a condition evaluator with the operand sources a workflow's guards use: literals, tags, and the
     * instance's variables.
     *
     * <p>Wired by hand, because the bundle plugin generates the DS metadata only at packaging time.</p>
     *
     * @return an evaluator a gateway's guards can be asked of
     * @throws ReflectiveOperationException when the injection fails, which would be a bug in this fixture
     */
    static ConditionEvaluator conditions() throws ReflectiveOperationException
    {
        final ConditionEvaluatorImpl evaluator = new ConditionEvaluatorImpl();
        final Field resolvers = ConditionEvaluatorImpl.class.getDeclaredField("resolvers");
        resolvers.setAccessible(true);
        resolvers.set(evaluator,
            List.of(new LiteralOperandResolver(), new TagsOperandResolver(), new VariableOperandResolver()));
        return evaluator;
    }

    /**
     * The engine's flow routing, asking the fixture's {@link #conditions() evaluator} about a gateway's guards.
     *
     * @return a routing for the engine and its task completion
     * @throws ReflectiveOperationException when the evaluator cannot be wired, which would be a bug in this fixture
     */
    static FlowRouting routing() throws ReflectiveOperationException
    {
        return new FlowRouting(conditions());
    }

    /**
     * Who the names in a definition stand for, as the fixture's {@link #principals() principal service} answers.
     *
     * @return the engine's performers
     */
    static Performers performers()
    {
        return new Performers(principals());
    }

    /**
     * How the engine grants read access on the resources its workflows drive.
     *
     * @return the engine's host access
     */
    static HostAccess hostAccess()
    {
        return new HostAccess(performers());
    }

    /**
     * Where the engine gets the runner that carries a user workflow on, routing through the fixture's
     * {@link #routing()}.
     *
     * @return the engine's instance runners
     * @throws ReflectiveOperationException when the evaluator cannot be wired, which would be a bug in this fixture
     */
    static InstanceRunners runners() throws ReflectiveOperationException
    {
        return new InstanceRunners(routing(), performers(), hostAccess());
    }

    /**
     * How the engine ends a user task.
     *
     * @return a task completion for the engine
     * @throws ReflectiveOperationException when the evaluator cannot be wired, which would be a bug in this fixture
     */
    static TaskCompletion taskCompletion() throws ReflectiveOperationException
    {
        return new TaskCompletion(performers(), runners());
    }

    /**
     * What the engine answers without changing anything, asking the fixture's {@link #conditions() evaluator} and
     * {@link #performers()}.
     *
     * @return the engine's queries
     * @throws ReflectiveOperationException when the evaluator cannot be wired, which would be a bug in this fixture
     */
    static WorkflowQueries queries() throws ReflectiveOperationException
    {
        return new WorkflowQueries(conditions(), performers());
    }

    /**
     * An engine wired as the DS runtime would wire it, over the given service sessions and handlers. Wiring is by
     * reflection, since the SCR metadata only exists in the packaged bundle.
     *
     * @param resolverFactory where the engine opens its own sessions
     * @param handlers the service task handlers to register
     * @return a ready engine
     * @throws ReflectiveOperationException when a field cannot be set, which would be a bug in this fixture
     */
    static WorkflowEngineImpl engine(final ResourceResolverFactory resolverFactory,
        final List<ServiceTaskHandler> handlers) throws ReflectiveOperationException
    {
        final WorkflowEngineImpl engine = new WorkflowEngineImpl();
        inject(engine, "resolverFactory", resolverFactory);
        inject(engine, "handlers", handlers);
        inject(engine, "conditionEvaluator", conditions());
        inject(engine, "routing", routing());
        inject(engine, "performers", performers());
        inject(engine, "runners", runners());
        inject(engine, "taskCompletion", taskCompletion());
        inject(engine, "queries", queries());
        inject(engine, "delegation", delegation("iap-administrators"));
        inject(engine, "recorder", new History());
        return engine;
    }

    /**
     * Sets one of the engine's references.
     *
     * @param engine the engine
     * @param field the reference's field name
     * @param value what to set it to
     * @throws ReflectiveOperationException when there is no such field
     */
    static void inject(final WorkflowEngineImpl engine, final String field, final Object value)
        throws ReflectiveOperationException
    {
        final Field reference = WorkflowEngineImpl.class.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(engine, value);
    }

    /**
     * Supplies the {@code sling:resourceType} that a real repository autocreates from the node type, which is the
     * one thing the mock one cannot do for itself. It matters because the runtime may not write that property:
     * every {@code wf:} type declares it protected, so a repository refuses. And because the engine reads what it
     * has just written back through the models, which recognise a node by exactly that type. Stamping afterwards
     * is not enough: the read happens inside the same call as the write.
     *
     * @param properties what the runtime asked to create
     * @return the same, plus the resource type the node type would have autocreated
     */
    private static Map<String, Object> autocreatedResourceType(final Map<String, Object> properties)
    {
        final Object primaryType = properties == null ? null : properties.get("jcr:primaryType");
        if (!(primaryType instanceof String) || !((String) primaryType).startsWith("wf:")
            || properties.containsKey(TYPE)) {
            return properties;
        }
        final Map<String, Object> stamped = new HashMap<>(properties);
        stamped.put(TYPE, ((String) primaryType).replace(':', '/'));
        return stamped;
    }

    /**
     * Wraps a resolver so that its session can be asked who the repository's users are and can hold access
     * control lists, the two things the mock repository has no notion of, and that anything authorizing or
     * granting cannot do without. Everything else stays the mock's own real behaviour.
     *
     * @param delegate the resolver to wrap
     * @return a resolver over a repository that answers those questions
     */
    public static ResourceResolver withRepositoryServices(final ResourceResolver delegate)
    {
        final JackrabbitSession session = jackrabbitSession(delegate.adaptTo(Session.class));
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? type.cast(session) : super.adaptTo(type);
            }
        };
    }

    /**
     * A session whose user store holds an administrator and one ordinary group member, and which accepts access
     * control lists. The mock repository has neither, so both have to be supplied for anything that authorizes or
     * grants to be exercised at all.
     *
     * @return a stub session
     */
    private static JackrabbitSession jackrabbitSession(final Session real)
    {
        try {
            // Built before the stubbing below starts: stubbing a mock inside an unfinished when() is the classic
            // way to confuse Mockito
            final User admin = user(ADMIN, true);
            final User requester = user(REQUESTER, false);
            final User timer = user(TaskCompletion.TIMER_USER, false);
            Mockito.when(timer.memberOf()).thenAnswer(invocation -> List.<Group>of().iterator());
            final Group requesters = group();
            final AccessControlManager accessControl = accessControlManager();
            final UserManager userManager = Mockito.mock(UserManager.class);
            Mockito.when(userManager.getAuthorizable(ADMIN)).thenReturn(admin);
            Mockito.when(userManager.getAuthorizable(REQUESTER)).thenReturn(requester);
            Mockito.when(userManager.getAuthorizable(TaskCompletion.TIMER_USER)).thenReturn(timer);
            Mockito.when(userManager.getAuthorizable(REQUESTERS)).thenReturn(requesters);
            // Membership is asked of the group, about the member, and only the ordinary user is in it
            Mockito.when(requesters.isMember(Mockito.any())).thenAnswer(invocation ->
                REQUESTER.equals(((Authorizable) invocation.getArgument(0)).getID()));
            final JackrabbitSession session =
                Mockito.mock(JackrabbitSession.class, AdditionalAnswers.delegatesTo(real));
            Mockito.doReturn(userManager).when(session).getUserManager();
            Mockito.doReturn(accessControl).when(session).getAccessControlManager();
            // The principal store knows no synchronised group, so a name the user store lacks names nobody
            Mockito.doReturn(Mockito.mock(PrincipalManager.class)).when(session).getPrincipalManager();
            // Not the actor, so membership is looked up in the user and principal stores, not read off this session
            Mockito.doReturn("the-engine").when(session).getUserID();
            return session;
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An access control manager that hands out a list and remembers what was granted on it.
     *
     * @return a stub manager
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static AccessControlManager accessControlManager() throws RepositoryException
    {
        final AccessControlList acl = Mockito.mock(AccessControlList.class);
        Mockito.when(acl.addAccessControlEntry(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            GRANTED.add(((Principal) invocation.getArgument(0)).getName());
            return true;
        });
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        Mockito.when(manager.getPolicies(Mockito.anyString()))
            .thenReturn(new AccessControlPolicy[] {acl});
        Mockito.when(manager.privilegeFromName(Mockito.anyString())).thenReturn(Mockito.mock(Privilege.class));
        return manager;
    }

    /**
     * The group the ordinary user belongs to, as an authorizable in its own right.
     *
     * @return a stub group
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static Group group() throws RepositoryException
    {
        final Principal principal = Mockito.mock(Principal.class);
        Mockito.when(principal.getName()).thenReturn(REQUESTERS);
        final Group group = Mockito.mock(Group.class);
        Mockito.when(group.getID()).thenReturn(REQUESTERS);
        Mockito.when(group.isGroup()).thenReturn(true);
        Mockito.when(group.getPrincipal()).thenReturn(principal);
        return group;
    }

    /**
     * One of the repository's users. The ordinary one belongs to {@link #REQUESTERS}; the administrator belongs
     * to nothing, since being an administrator is what gets them through.
     *
     * @param id the user id
     * @param admin whether this user is an administrator
     * @return a stub user
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static User user(final String id, final boolean admin) throws RepositoryException
    {
        final Group requesters = group();
        final Principal principal = Mockito.mock(Principal.class);
        Mockito.when(principal.getName()).thenReturn(id);
        final User user = Mockito.mock(User.class);
        Mockito.when(user.getPrincipal()).thenReturn(principal);
        Mockito.when(user.getID()).thenReturn(id);
        Mockito.when(user.isAdmin()).thenReturn(admin);
        Mockito.when(user.memberOf()).thenAnswer(invocation -> admin
            ? List.<Group>of().iterator()
            : List.of(requesters).iterator());
        return user;
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
