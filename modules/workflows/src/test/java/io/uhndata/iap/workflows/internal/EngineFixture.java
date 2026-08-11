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

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

import io.uhndata.iap.tags.internal.TagOperations;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.models.WorkflowsHomepage;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;

/**
 * Shared setup for the engine tests: the {@code /Workflows} homepage events are aimed at, builders for system
 * workflow definitions of various shapes under {@code /SystemWorkflows}, and a stand-in for the user store that
 * the mock repository does not have but authorization cannot do without.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EngineFixture
{
    /** The path of the bootstrap system workflow the tests build. */
    static final String WORKFLOW = SystemWorkflowsHomepage.PATH + "/createWorkflow";

    /** The path of the version node of the bootstrap system workflow. */
    static final String VERSION = WORKFLOW + "/v1";

    /** An administrator, who passes every performer check. */
    static final String ADMIN = "admin";

    /** An ordinary user belonging to {@link #REQUESTERS}. */
    static final String REQUESTER = "demo-requester";

    /** The group {@link #REQUESTER} belongs to. */
    static final String REQUESTERS = "time-off-requesters";

    /** Every principal granted read since the last fixture was built, for the access tests to assert on. */
    static final List<String> GRANTED = new ArrayList<>();

    /** The category the lifecycle states share, and therefore retire each other through. */
    static final String LIFECYCLE = "lifecycle";

    /** The lifecycle states these tests move a host through. */
    static final List<String> STATES = List.of("draft", "submitted", "in-review", "approved", "rejected");

    private EngineFixture()
    {
    }

    /**
     * A stand-in for the tag vocabulary, knowing only the lifecycle states these tests use.
     *
     * <p>The mock repository holds no {@code iap:TagDefinition} nodes, so the service the {@code Taggable} model
     * reads the vocabulary through is what has to be supplied. It keeps the tags in the node's own {@code tags}
     * property, which is where the real one puts them, so the assertions read the same place production writes.</p>
     *
     * @return a tag service covering the {@link #LIFECYCLE} category
     */
    static TagOperations lifecycleTags()
    {
        final List<TagDefinition> definitions = STATES.stream()
            .map(EngineFixture::state)
            .collect(Collectors.toList());
        final TagOperations operations = Mockito.mock(TagOperations.class);
        Mockito.when(operations.getApplicableDefinitions(Mockito.any())).thenReturn(definitions);
        Mockito.when(operations.getTags(Mockito.any())).thenAnswer(call -> tagsOf(call.getArgument(0)));
        try {
            Mockito.doAnswer(call -> {
                final Resource resource = call.getArgument(0);
                final Collection<String> names = call.getArgument(1);
                Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class))
                    .put("tags", names.toArray(String[]::new));
                return null;
            }).when(operations).setTags(Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        } catch (final PersistenceException e) {
            throw new IllegalStateException("Stubbing does not touch the repository", e);
        }
        return operations;
    }

    /**
     * The tags a node carries, read the way the tag service stores them.
     *
     * @param resource the node to read
     * @return its tag names, empty if it carries none
     */
    static Set<String> tagsOf(final Resource resource)
    {
        return new LinkedHashSet<>(Arrays.asList(resource.getValueMap().get("tags", new String[0])));
    }

    /**
     * One lifecycle state's definition. Built into a local before the vocabulary is stubbed with it, since Mockito
     * rejects a mock built inside an unfinished {@code when}.
     *
     * @param name the state's tag name
     * @return its definition
     */
    private static TagDefinition state(final String name)
    {
        final TagDefinition definition = Mockito.mock(TagDefinition.class);
        Mockito.when(definition.getName()).thenReturn(name);
        Mockito.when(definition.getCategories()).thenReturn(List.of(LIFECYCLE));
        return definition;
    }

    /**
     * Creates the {@code /Workflows} homepage the tests aim their events at, posted to by an administrator.
     *
     * @param context the Sling context to build in
     * @return the homepage resource
     */
    static Resource createTarget(final SlingContext context)
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
    static Resource createTarget(final SlingContext context, final String actor)
    {
        final Resource homepage = context.create().resource("/Workflows", TYPE, WorkflowsHomepage.RESOURCE_TYPE);
        final ResourceResolver resolver = new ResourceResolverWrapper(homepage.getResourceResolver())
        {
            @Override
            public String getUserID()
            {
                return actor;
            }
        };
        return new ResourceWrapper(homepage)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
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
                // Delegating rather than replacing: everything the runtime does with the session — resolving
                // references by identifier, reading nodes — must still be the mock repository's real behaviour,
                // with only the two things it has no notion of supplied on top
                return new ResourceResolverWrapper(withRepositoryServices(delegate))
                {
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
     * Wraps a resolver so that its session can be asked who the repository's users are and can hold access
     * control lists — the two things the mock repository has no notion of, and that anything authorizing or
     * granting cannot do without. Everything else stays the mock's own real behaviour.
     *
     * @param delegate the resolver to wrap
     * @return a resolver over a repository that answers those questions
     */
    static ResourceResolver withRepositoryServices(final ResourceResolver delegate)
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
            final Group requesters = group();
            final AccessControlManager accessControl = accessControlManager();
            final UserManager userManager = Mockito.mock(UserManager.class);
            Mockito.when(userManager.getAuthorizable(ADMIN)).thenReturn(admin);
            Mockito.when(userManager.getAuthorizable(REQUESTER)).thenReturn(requester);
            Mockito.when(userManager.getAuthorizable(REQUESTERS)).thenReturn(requesters);
            final JackrabbitSession session =
                Mockito.mock(JackrabbitSession.class, AdditionalAnswers.delegatesTo(real));
            Mockito.doReturn(userManager).when(session).getUserManager();
            Mockito.doReturn(accessControl).when(session).getAccessControlManager();
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
     * Creates {@code /SystemWorkflows} holding one definition with one version, both active unless told
     * otherwise, targeting {@code wf/WorkflowsHomepage} — without any flow nodes yet.
     *
     * @param context the Sling context to build in
     * @param definitionActive whether the definition accepts instantiation
     * @param versionActive whether the version accepts instantiation
     * @param targetResourceType the resource type the version declares itself for, or {@code null} for none
     */
    static void createSystemWorkflow(final SlingContext context, final boolean definitionActive,
        final boolean versionActive, final String targetResourceType)
    {
        context.create().resource(SystemWorkflowsHomepage.PATH, TYPE, SystemWorkflowsHomepage.RESOURCE_TYPE);
        context.create().resource(WORKFLOW, Map.of(
            TYPE, WorkflowDefinition.RESOURCE_TYPE, "title", "Create a workflow", "active", definitionActive));
        if (targetResourceType == null) {
            context.create().resource(VERSION, Map.of(
                TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", versionActive));
        } else {
            context.create().resource(VERSION, Map.of(
                TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", versionActive,
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
    static void createBootstrapGraph(final SlingContext context, final String... performers)
    {
        context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "requested", "messageName", "create",
            "performers", performers));
        context.create().resource(VERSION + "/requested/toCreate", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "toCreate", "targetRef", "create"));
        context.create().resource(VERSION + "/create", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "create",
            "handler", CreateEntityHandler.NAME, "entityType", "wf:WorkflowDefinition"));
        context.create().resource(VERSION + "/create/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "toDone", "targetRef", "done"));
        context.create().resource(VERSION + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "done"));
    }
}
