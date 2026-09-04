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

import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.GroupPrincipal;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.FlowNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PerformerCheck}, which is where the platform decides what a user is allowed to do. Since
 * nothing downstream will refuse an actor who gets past here — the engine works privileged — these cases are worth
 * being exhaustive about, especially the ones that must refuse.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PerformerCheckTest
{
    private static final String REQUESTERS = "time-off-requesters";

    private static final String REQUESTER = "demo-requester";

    @Test
    void admitsAnActorTheNodeNamesDirectly() throws Exception
    {
        assertDoesNotThrow(() -> verify(
            repositoryWith(user(REQUESTER, false)), host(), node(REQUESTER), REQUESTER));
    }

    @Test
    void admitsAnActorThroughTheirGroup() throws Exception
    {
        assertDoesNotThrow(() -> verify(
            repositoryWith(user(REQUESTER, false), REQUESTERS), host(), node(REQUESTERS), REQUESTER));
    }

    // The gap the shared vocabulary closed: a group an identity provider synchronises has no local node at all,
    // so the user store answers null for its very name and only the principal store can admit through it
    @Test
    void admitsAnActorThroughADynamicGroup() throws Exception
    {
        final ResourceResolver resolver = repositoryWith(user(REQUESTER, false));
        final GroupPrincipal dynamic = Mockito.mock(GroupPrincipal.class);
        Mockito.when(dynamic.isMember(Mockito.any())).thenReturn(true);
        final PrincipalManager principalManager =
            ((JackrabbitSession) resolver.adaptTo(Session.class)).getPrincipalManager();
        Mockito.when(principalManager.getPrincipal("keycloak-role")).thenReturn(dynamic);
        assertDoesNotThrow(() -> verify(resolver, host(), node("keycloak-role"), REQUESTER));
    }

    @Test
    void admitsAnyAuthenticatedActorWhenTheNodeNamesEveryone() throws Exception
    {
        // "everyone" is matched by name: it is a dynamic principal, so an actor need not report belonging to it
        assertDoesNotThrow(() -> verify(
            repositoryWith(user(REQUESTER, false)), host(), node("everyone"), REQUESTER));
    }

    @Test
    void admitsAdministratorsWhateverTheNodeSays() throws Exception
    {
        // The break-glass: without it, one bad definition could lock out the very people who could fix it
        assertDoesNotThrow(() -> verify(
            repositoryWith(user("admin", true)), host(), node(), "admin"));
    }

    @Test
    void refusesAnActorTheNodeDoesNotName() throws Exception
    {
        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> verify(
                repositoryWith(user(REQUESTER, false), REQUESTERS), host(), node("time-off-approvers"),
                REQUESTER));
        assertTrue(refusal.getMessage().contains("not allowed"));
    }

    @Test
    void refusesEveryoneWhenTheNodeNamesNobody() throws Exception
    {
        // The fail-closed rule: a definition that forgot to say who may use it admits no one, rather than all
        assertThrows(NotAuthorizedException.class, () -> verify(
            repositoryWith(user(REQUESTER, false), REQUESTERS), host(), node(), REQUESTER));
    }

    @Test
    void refusesAnActorTheRepositoryHasNeverHeardOf() throws Exception
    {
        assertThrows(NotAuthorizedException.class, () -> verify(
            repositoryWith(user(REQUESTER, false)), host(), node("everyone"), "nobody"));
    }

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception
    {
        assertThrows(NotAuthorizedException.class, () -> verify(
            repositoryWith(user(REQUESTER, false)), host(), node("everyone"), null));
    }

    @Test
    void admitsWhoeverRaisedTheResourceWhenTheNodeNamesTheCreator() throws Exception
    {
        // The rule no group can express: a request comes back to the person who made it
        assertDoesNotThrow(() -> verify(
            repositoryWith(user(REQUESTER, false)), raisedBy(REQUESTER), node("@creator"), REQUESTER));
    }

    @Test
    void refusesSomebodyElseWhenTheNodeOnlyNamesTheCreator() throws Exception
    {
        assertThrows(NotAuthorizedException.class, () -> verify(
            repositoryWith(user(REQUESTER, false), REQUESTERS), raisedBy("someone-else"), node("@creator"),
            REQUESTER));
    }

    @Test
    void refusesEveryoneWhenTheNodeNamesTheCreatorOfSomethingNobodyRaised() throws Exception
    {
        // A homepage, say: nothing recorded raising it, so it is nobody's and admits nobody
        assertThrows(NotAuthorizedException.class, () -> verify(
            repositoryWith(user(REQUESTER, false)), host(), node("@creator"), REQUESTER));
    }

    @Test
    void stillAdmitsANamedGroupOnANodeThatAlsoNamesTheCreator() throws Exception
    {
        // @creator is one more name among the performers, not a mode the node switches into
        assertDoesNotThrow(() -> verify(
            repositoryWith(user(REQUESTER, false), REQUESTERS), raisedBy("someone-else"),
            node("@creator", REQUESTERS), REQUESTER));
    }

    @Test
    void failsWhenTheRepositoryHasNoUserStore()
    {
        // Not a refusal: a repository that cannot say who its users are is broken, not restrictive
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(Mockito.mock(Session.class));

        assertThrows(WorkflowFailedException.class,
            () -> verify(resolver, host(), node("everyone"), REQUESTER));
    }

    @Test
    void failsWhenTheUserStoreCannotBeReached() throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenThrow(new RepositoryException("the user store is down"));
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);

        assertThrows(WorkflowFailedException.class,
            () -> verify(resolver, host(), node("everyone"), REQUESTER));
    }

    @Test
    void failsWhenGroupMembershipCannotBeRead() throws Exception
    {
        final ResourceResolver resolver = repositoryWith(user(REQUESTER, false));
        final UserManager users = ((JackrabbitSession) resolver.adaptTo(Session.class)).getUserManager();
        Mockito.when(users.getAuthorizable(REQUESTERS))
            .thenThrow(new RepositoryException("the group index is corrupt"));

        assertThrows(WorkflowFailedException.class,
            () -> verify(resolver, host(), node(REQUESTERS), REQUESTER));
    }

    /**
     * Runs the check with the shared vocabulary the engine wires in.
     *
     * @param resolver the stub repository to check against
     * @param host the resource being worked on
     * @param node the flow node execution wants to pass through
     * @param actor the user who fired the event
     * @throws Exception when the check refuses or fails, which each case asserts on
     */
    private static void verify(final ResourceResolver resolver, final Resource host, final FlowNode node,
        final String actor) throws Exception
    {
        PerformerCheck.verify(EngineFixture.principals(), resolver, host, node, actor);
    }

    /**
     * A flow node admitting the given principals.
     *
     * @param performers the principals the node names, none for a node that admits nobody
     * @return a stub flow node
     */
    private static FlowNode node(final String... performers)
    {
        final FlowNode node = Mockito.mock(FlowNode.class);
        Mockito.when(node.getPerformers()).thenReturn(List.of(performers));
        return node;
    }

    /**
     * A resource nothing is recorded as having raised.
     *
     * @return a stub resource that does not read as content and carries no record of its own
     */
    private static Resource host()
    {
        final Resource host = Mockito.mock(Resource.class);
        Mockito.when(host.getValueMap()).thenReturn(new ValueMapDecorator(Map.of()));
        return host;
    }

    /**
     * A resource the engine recorded somebody as having raised.
     *
     * @param raiser the user id recorded on it
     * @return a stub resource reading as content with that creator
     */
    private static Resource raisedBy(final String raiser)
    {
        final Content content = Mockito.mock(Content.class);
        Mockito.when(content.getCreatedBy()).thenReturn(raiser);
        final Resource host = Mockito.mock(Resource.class);
        Mockito.when(host.adaptTo(Content.class)).thenReturn(content);
        return host;
    }

    /**
     * An actor, optionally an administrator.
     *
     * @param id the actor's user id
     * @param admin whether the actor is an administrator
     * @return a stub user
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static User user(final String id, final boolean admin) throws RepositoryException
    {
        final User actor = Mockito.mock(User.class);
        Mockito.when(actor.getID()).thenReturn(id);
        Mockito.when(actor.isAdmin()).thenReturn(admin);
        Mockito.when(actor.getPrincipal()).thenReturn(() -> id);
        return actor;
    }

    /**
     * A session whose user store knows exactly the given actor, plus the groups they belong to.
     *
     * @param known the one user the repository has
     * @param memberships local groups the user is a member of
     * @return a resolver adapting to that repository
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver repositoryWith(final Authorizable known, final String... memberships)
        throws RepositoryException
    {
        final UserManager userManager = Mockito.mock(UserManager.class);
        Mockito.when(userManager.getAuthorizable(known.getID())).thenReturn(known);
        for (final String membership : memberships) {
            final Group group = Mockito.mock(Group.class);
            Mockito.when(group.isGroup()).thenReturn(true);
            Mockito.when(group.isMember(known)).thenReturn(true);
            Mockito.when(userManager.getAuthorizable(membership)).thenReturn(group);
        }
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenReturn(userManager);
        Mockito.when(session.getPrincipalManager()).thenReturn(Mockito.mock(PrincipalManager.class));
        Mockito.when(session.getUserID()).thenReturn("the-engine");
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        return resolver;
    }
}
