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
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HostAccess}: turning a workflow's declared performers into read access on the resource it
 * drives, which is the one place where the definitions' answer and the repository's have to be made to agree.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class HostAccessTest
{
    private static final String VERSION = "/Workflows/timeOffRequest/v1";

    private static final String HOST = "/Submissions/aLongWeekend";

    private static final String APPROVERS = "time-off-approvers";

    private final SlingContext context = new SlingContext();

    private final List<String> granted = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(HOST, TYPE, "sub/Submission");
        this.context.create().resource(VERSION, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        // A user task, whose performers are people the process involves, and a service task, whose "performers"
        // are nobody at all — a handler is not somebody who needs to see anything
        this.context.create().resource(VERSION + "/approve", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "approve", "performers", new String[] {APPROVERS}));
        this.context.create().resource(VERSION + "/record", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "record", "handler", "noop",
            "performers", new String[] {"a-service-account"}));
    }

    private WorkflowVersion version()
    {
        return this.context.resourceResolver().getResource(VERSION).adaptTo(WorkflowVersion.class);
    }

    private Resource host()
    {
        return this.context.resourceResolver().getResource(HOST);
    }

    @Test
    void grantsReadToTheActorAndTheUserTaskPerformers() throws Exception
    {
        HostAccess.grantReaders(resolver(list()), host(), version(), "demo-requester");

        assertEquals(List.of("demo-requester", APPROVERS), this.granted);
    }

    @Test
    void asksTheRepositoryForAListWhenTheResourceHasNoneYet() throws Exception
    {
        final AccessControlList acl = list();
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        // A resource can carry policies that are not lists; those are skipped rather than mistaken for one
        Mockito.when(manager.getPolicies(Mockito.anyString()))
            .thenReturn(new AccessControlPolicy[] {Mockito.mock(AccessControlPolicy.class)});
        final AccessControlPolicyIterator applicable = Mockito.mock(AccessControlPolicyIterator.class);
        Mockito.when(applicable.hasNext()).thenReturn(true, true);
        Mockito.when(applicable.nextAccessControlPolicy())
            .thenReturn(Mockito.mock(AccessControlPolicy.class), acl);
        Mockito.when(manager.getApplicablePolicies(Mockito.anyString())).thenReturn(applicable);
        Mockito.when(manager.privilegeFromName(Mockito.anyString())).thenReturn(Mockito.mock(Privilege.class));

        HostAccess.grantReaders(resolverFor(manager), host(), version(), "demo-requester");

        assertEquals(List.of("demo-requester", APPROVERS), this.granted);
    }

    @Test
    void failsWhenTheResourceCannotHoldOne() throws Exception
    {
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        Mockito.when(manager.getPolicies(Mockito.anyString())).thenReturn(new AccessControlPolicy[0]);
        final AccessControlPolicyIterator applicable = Mockito.mock(AccessControlPolicyIterator.class);
        Mockito.when(applicable.hasNext()).thenReturn(false);
        Mockito.when(manager.getApplicablePolicies(Mockito.anyString())).thenReturn(applicable);

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> HostAccess.grantReaders(resolverFor(manager), host(), version(), "demo-requester"));
        assertTrue(failure.getMessage().contains("Could not grant read access"));
    }

    @Test
    void skipsPrincipalsThisDeploymentDoesNotHave() throws Exception
    {
        // A definition may perfectly well name a group a given deployment has never created; that is not a reason
        // to refuse to start the workflow
        HostAccess.grantReaders(resolver(list(), "demo-requester"), host(), version(), "demo-requester");

        assertEquals(List.of("demo-requester"), this.granted);
    }

    @Test
    void failsWithoutARepositoryThatKnowsItsUsers()
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(Mockito.mock(Session.class));

        assertThrows(PersistenceException.class,
            () -> HostAccess.grantReaders(resolver, host(), version(), "demo-requester"));
    }

    /**
     * An access control list that records what it was asked to grant.
     *
     * @return a stub list
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private AccessControlList list() throws RepositoryException
    {
        final AccessControlList acl = Mockito.mock(AccessControlList.class);
        Mockito.when(acl.addAccessControlEntry(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            this.granted.add(((Principal) invocation.getArgument(0)).getName());
            return true;
        });
        return acl;
    }

    /**
     * A resolver whose repository holds every named principal.
     *
     * @param acl the list it hands out
     * @param known the principals it has, or none for "all of them"
     * @return a stub resolver
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private ResourceResolver resolver(final AccessControlList acl, final String... known)
        throws RepositoryException
    {
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        Mockito.when(manager.getPolicies(Mockito.anyString())).thenReturn(new AccessControlPolicy[] {acl});
        Mockito.when(manager.privilegeFromName(Mockito.anyString())).thenReturn(Mockito.mock(Privilege.class));
        return resolverFor(manager, known);
    }

    /**
     * A resolver over a repository with the given access control manager.
     *
     * @param manager the manager it exposes
     * @param known the principals it has, or none for "all of them"
     * @return a stub resolver
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private ResourceResolver resolverFor(final AccessControlManager manager, final String... known)
        throws RepositoryException
    {
        final UserManager users = Mockito.mock(UserManager.class);
        Mockito.when(users.getAuthorizable(Mockito.anyString())).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            if (known.length > 0 && !List.of(known).contains(name)) {
                return null;
            }
            final Principal principal = Mockito.mock(Principal.class);
            Mockito.when(principal.getName()).thenReturn(name);
            final Authorizable authorizable = Mockito.mock(Authorizable.class);
            Mockito.when(authorizable.getPrincipal()).thenReturn(principal);
            return authorizable;
        });
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenReturn(users);
        Mockito.when(session.getAccessControlManager()).thenReturn(manager);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        return resolver;
    }
}
