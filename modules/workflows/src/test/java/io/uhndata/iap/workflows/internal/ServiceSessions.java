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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.InvalidItemStateException;
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
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.osgi.context.AbstractContextPlugin;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.utils.testing.TestResolverFactory;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;

/**
 * The engine's service sessions over a mock repository. Installed before
 * {@link io.uhndata.iap.workflows.testing.WorkflowPlugins#ENGINE}, it is where the engine opens its sessions:
 *
 * {@snippet :
 * private final ServiceSessions sessions = new ServiceSessions();
 *
 * private final SlingContext context = new SlingContextBuilder()
 *     .plugin(this.sessions, WorkflowPlugins.ENGINE)
 *     .build();
 * }
 *
 * <p>A session is the context's own service session with what the mock repository lacks supplied on top. Its user
 * store holds an {@link EngineFixture#ADMIN administrator}, an {@link EngineFixture#REQUESTER ordinary user} in
 * {@link EngineFixture#REQUESTERS a group}, and the timer. It accepts access control lists and remembers who was
 * {@link #granted() granted} read. A node it creates gets the {@code sling:resourceType} its {@code wf:} node type
 * would autocreate.</p>
 *
 * <p>A test can make the sessions misbehave: run something before each commit, which may refuse it, or refuse the
 * login itself. It can also see every session the engine {@link #opened() opened}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ServiceSessions extends AbstractContextPlugin<SlingContextImpl>
{
    /** Something done to the repository before each of the engine's commits, by default nothing. */
    private static final RepositoryAction NOTHING = () -> { };

    private final List<String> granted = new ArrayList<>();

    private final List<ServiceSession> opened = new ArrayList<>();

    private RepositoryAction beforeCommit = NOTHING;

    private boolean refusingLogins;

    private ResourceResolverFactory contextFactory;

    /**
     * Something done to the repository that may fail as a commit does.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface RepositoryAction
    {
        /**
         * Acts on the repository. Run as another writer, it makes and commits that writer's change. Run before
         * one of the engine's commits, it may refuse that commit by throwing.
         *
         * @throws PersistenceException when the repository refuses the change, or to refuse the commit it precedes
         */
        void run() throws PersistenceException;
    }

    /**
     * Run before each of the engine's commits, loses the first to another writer: the winner's change lands, and
     * the commit is refused as Oak refuses a conflicting one. Every later commit goes through.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class OneLostRace implements RepositoryAction
    {
        /** A winner whose change the losing event does not read. The stand-in writes nothing. */
        public static final RepositoryAction UNRELATED_CHANGE = NOTHING;

        private final RepositoryAction winner;

        private boolean lost;

        /**
         * Constructor.
         *
         * @param winner the other writer's change, committed through its own session
         */
        public OneLostRace(final RepositoryAction winner)
        {
            this.winner = winner;
        }

        @Override
        public void run() throws PersistenceException
        {
            if (!this.lost) {
                this.lost = true;
                this.winner.run();
                throw conflict();
            }
        }

        /**
         * Whether the race was lost: whether the engine committed at all.
         *
         * @return {@code true} once the first commit has been refused
         */
        public boolean wasLost()
        {
            return this.lost;
        }
    }

    /**
     * One of the engine's sessions, telling whether it was closed.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public final class ServiceSession extends ResourceResolverWrapper
    {
        private boolean closed;

        private ServiceSession(final ResourceResolver delegate)
        {
            super(withRepositoryServices(delegate));
        }

        @Override
        public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
            throws PersistenceException
        {
            return super.create(parent, name, autocreatedResourceType(properties));
        }

        @Override
        public void commit() throws PersistenceException
        {
            ServiceSessions.this.beforeCommit.run();
            super.commit();
        }

        @Override
        public void revert()
        {
            // The mock session cannot refresh, so rolling back is beyond it. That the engine reverts a failed run is
            // asserted against a real repository by the integration tests; here the point is only that a failure
            // surfaces as the right exception rather than as this one.
            try {
                super.revert();
            } catch (final UnsupportedOperationException e) {
                // Nothing this repository can do
            }
        }

        @Override
        public void close()
        {
            this.closed = true;
            super.close();
        }

        /**
         * Whether the engine has closed this session.
         *
         * @return {@code true} once closed
         */
        public boolean wasClosed()
        {
            return this.closed;
        }
    }

    @Override
    public void afterSetUp(final SlingContextImpl context)
    {
        this.contextFactory = context.getService(ResourceResolverFactory.class);
        context.registerService(ResourceResolverFactory.class, TestResolverFactory.answeredBy(this::open),
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
    }

    /**
     * A refused commit, as Oak refuses one that conflicts with a change another session committed first.
     *
     * @return the refusal, ready to be thrown
     */
    public static PersistenceException conflict()
    {
        return new PersistenceException("save failed", new InvalidItemStateException("this node has been modified"));
    }

    /**
     * Runs something before each of the engine's commits from now on.
     *
     * @param action what runs before each commit, and may refuse it
     */
    public void beforeEachCommit(final RepositoryAction action)
    {
        this.beforeCommit = action;
    }

    /**
     * Refuses each of the engine's commits from now on. The writes themselves succeed.
     *
     * @param failure what each commit throws
     */
    public void failEachCommitWith(final PersistenceException failure)
    {
        beforeEachCommit(() -> {
            throw failure;
        });
    }

    /** Refuses the engine's logins from now on, as Sling does for a service user that is not mapped. */
    public void refuseLogins()
    {
        this.refusingLogins = true;
    }

    /**
     * Every principal granted read through these sessions, for the access tests to assert on.
     *
     * @return the principals' names, in the order they were granted
     */
    public List<String> granted()
    {
        return List.copyOf(this.granted);
    }

    /**
     * Every session the engine opened.
     *
     * @return the sessions, in the order they were opened
     */
    public List<ServiceSession> opened()
    {
        return List.copyOf(this.opened);
    }

    /**
     * A new session of the context's own, not the engine's, as a user asking the engine would have.
     *
     * @return a new session, which the caller closes
     * @throws LoginException never, since the mock factory logs anyone in
     */
    public ResourceResolver openUserSession() throws LoginException
    {
        return this.contextFactory.getResourceResolver(null);
    }

    /**
     * Wraps a resolver so that its session can be asked who the repository's users are and can hold access
     * control lists, the two things the mock repository has no notion of, and that anything authorizing or
     * granting cannot do without. Everything else stays the mock's own real behaviour.
     *
     * @param delegate the resolver to wrap
     * @return a resolver over a repository that answers those questions
     */
    public ResourceResolver withRepositoryServices(final ResourceResolver delegate)
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

    private ResourceResolver open(final Map<String, Object> authenticationInfo) throws LoginException
    {
        if (this.refusingLogins) {
            throw new LoginException("No such service user");
        }
        // Delegating rather than replacing. Everything the runtime does with the session, resolving references by
        // identifier and reading nodes, must still be the mock repository's behaviour, with only the things it has
        // no notion of supplied on top
        final ServiceSession session =
            new ServiceSession(this.contextFactory.getServiceResourceResolver(authenticationInfo));
        this.opened.add(session);
        return session;
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
     * A session whose user store holds an administrator and one ordinary group member, and which accepts access
     * control lists. The mock repository has neither, so both have to be supplied for anything that authorizes or
     * grants to be exercised at all.
     *
     * @return a stub session
     */
    private JackrabbitSession jackrabbitSession(final Session real)
    {
        try {
            // Built before the stubbing below starts: stubbing a mock inside an unfinished when() is the classic
            // way to confuse Mockito
            final User admin = administrator(EngineFixture.ADMIN);
            final User requester = user(EngineFixture.REQUESTER, group());
            final User timer = user(TaskDeadline.TIMER_USER);
            final Group requesters = group();
            final AccessControlManager accessControl = accessControlManager();
            final UserManager userManager = Mockito.mock(UserManager.class);
            Mockito.when(userManager.getAuthorizable(EngineFixture.ADMIN)).thenReturn(admin);
            Mockito.when(userManager.getAuthorizable(EngineFixture.REQUESTER)).thenReturn(requester);
            Mockito.when(userManager.getAuthorizable(TaskDeadline.TIMER_USER)).thenReturn(timer);
            Mockito.when(userManager.getAuthorizable(EngineFixture.REQUESTERS)).thenReturn(requesters);
            // Membership is asked of the group, about the member, and only the ordinary user is in it
            Mockito.when(requesters.isMember(Mockito.any())).thenAnswer(invocation ->
                EngineFixture.REQUESTER.equals(((Authorizable) invocation.getArgument(0)).getID()));
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
    private AccessControlManager accessControlManager() throws RepositoryException
    {
        final AccessControlList acl = Mockito.mock(AccessControlList.class);
        Mockito.when(acl.addAccessControlEntry(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            this.granted.add(((Principal) invocation.getArgument(0)).getName());
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
        Mockito.when(principal.getName()).thenReturn(EngineFixture.REQUESTERS);
        final Group group = Mockito.mock(Group.class);
        Mockito.when(group.getID()).thenReturn(EngineFixture.REQUESTERS);
        Mockito.when(group.isGroup()).thenReturn(true);
        Mockito.when(group.getPrincipal()).thenReturn(principal);
        return group;
    }

    /**
     * An administrator, who belongs to nothing, since being an administrator is what gets them through.
     *
     * @param id the user id
     * @return a stub user
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static User administrator(final String id) throws RepositoryException
    {
        final User administrator = user(id);
        Mockito.when(administrator.isAdmin()).thenReturn(true);
        return administrator;
    }

    /**
     * One of the repository's ordinary users.
     *
     * @param id the user id
     * @param groups the groups the user belongs to
     * @return a stub user
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static User user(final String id, final Group... groups) throws RepositoryException
    {
        final Principal principal = Mockito.mock(Principal.class);
        Mockito.when(principal.getName()).thenReturn(id);
        final User user = Mockito.mock(User.class);
        Mockito.when(user.getPrincipal()).thenReturn(principal);
        Mockito.when(user.getID()).thenReturn(id);
        Mockito.when(user.memberOf()).thenAnswer(invocation -> List.of(groups).iterator());
        return user;
    }
}
