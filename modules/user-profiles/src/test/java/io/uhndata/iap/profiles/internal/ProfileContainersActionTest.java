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
package io.uhndata.iap.profiles.internal;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileContainersAction}, pinning the contract of the callback: what it adds, what it leaves
 * alone, and that it refuses rather than half-prepares an account.
 *
 * <p>
 * Which accounts Oak actually invokes it for, and that the containers make the restricted write grant sufficient, are
 * properties of a real repository and were measured against one; nothing reachable from here can show either, because
 * the test harness cannot install an action into its security provider.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class ProfileContainersActionTest
{
    private static final String HOME = "/home/users/j/jd/jdoe";

    private ProfileContainersAction action;

    private Root root;

    private Tree home;

    private NamePathMapper mapper;

    private User user;

    @BeforeEach
    void setUp() throws Exception
    {
        this.action = new ProfileContainersAction();
        this.root = mock(Root.class);
        this.home = mock(Tree.class);
        this.mapper = mock(NamePathMapper.class);
        this.user = mock(User.class);

        when(this.user.getPath()).thenReturn(HOME);
        when(this.user.getID()).thenReturn("jdoe");
        when(this.mapper.getOakPath(HOME)).thenReturn(HOME);
        when(this.root.getTree(HOME)).thenReturn(this.home);
        when(this.home.exists()).thenReturn(true);
    }

    @Test
    void addsBothContainersToANewAccount() throws Exception
    {
        final Tree profile = mock(Tree.class);
        final Tree preferences = mock(Tree.class);
        when(this.home.hasChild(anyString())).thenReturn(false);
        when(this.home.addChild(ProfileContainersAction.PROFILE)).thenReturn(profile);
        when(this.home.addChild(ProfileContainersAction.PREFERENCES)).thenReturn(preferences);

        this.action.onCreate(this.user, "secret", this.root, this.mapper);

        // The type matters as much as the node: Oak's own claim mapping leaves nt:unstructured behind, so an account
        // synced before this action existed has to agree with what it makes.
        verify(profile).setProperty(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_UNSTRUCTURED, Type.NAME);
        verify(preferences).setProperty(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_UNSTRUCTURED, Type.NAME);
    }

    @Test
    void worksForAnAccountWithNoPassword() throws Exception
    {
        // An identity provider creates accounts this way, and it is the case the whole change exists for
        when(this.home.hasChild(anyString())).thenReturn(false);
        when(this.home.addChild(anyString())).thenReturn(mock(Tree.class));

        this.action.onCreate(this.user, null, this.root, this.mapper);

        verify(this.home).addChild(ProfileContainersAction.PROFILE);
        verify(this.home).addChild(ProfileContainersAction.PREFERENCES);
    }

    @Test
    void leavesAContainerThatIsAlreadyThere() throws Exception
    {
        when(this.home.hasChild(ProfileContainersAction.PROFILE)).thenReturn(true);
        when(this.home.hasChild(ProfileContainersAction.PREFERENCES)).thenReturn(false);
        when(this.home.addChild(ProfileContainersAction.PREFERENCES)).thenReturn(mock(Tree.class));

        this.action.onCreate(this.user, "secret", this.root, this.mapper);

        verify(this.home, never()).addChild(ProfileContainersAction.PROFILE);
        verify(this.home).addChild(ProfileContainersAction.PREFERENCES);
    }

    @Test
    void refusesRatherThanHandBackAnAccountItCouldNotPrepare()
    {
        when(this.home.exists()).thenReturn(false);

        final RepositoryException thrown = assertThrows(RepositoryException.class,
            () -> this.action.onCreate(this.user, "secret", this.root, this.mapper));

        // The account id belongs in the message: this surfaces as a failed sign-in, and an operator needs to know whose
        assertTrue(thrown.getMessage().contains("jdoe"), thrown.getMessage());
        verify(this.home, never()).addChild(anyString());
    }

    @Test
    void refusesWhenTheAccountsPathCannotBeMapped()
    {
        // Oak answers null for a name it cannot map, and feeding that to getTree would fail further away from the cause
        when(this.mapper.getOakPath(HOME)).thenReturn(null);

        assertThrows(RepositoryException.class,
            () -> this.action.onCreate(this.user, "secret", this.root, this.mapper));
    }

    @Test
    void offersExactlyThisActionToOak()
    {
        assertEquals(1, new ProfileContainersActionProvider()
            .getAuthorizableActions(null).stream()
            .filter(ProfileContainersAction.class::isInstance)
            .count());
    }
}
