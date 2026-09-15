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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.PrincipalIterator;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.api.Requester;
import io.uhndata.iap.profiles.api.UpdateOutcome;
import io.uhndata.iap.profiles.api.UserProfileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserProfileServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UserProfileServletTest
{
    private static final String ME = "jdoe";

    private final SlingContext context = new SlingContext();

    private UserProfileService profiles;

    private UserProfileServlet servlet;

    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private ResourceResolver resolver;

    private RequestPathInfo pathInfo;

    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception
    {
        this.profiles = mock(UserProfileService.class);
        this.servlet = new UserProfileServlet(this.profiles);

        this.resolver = mock(ResourceResolver.class);
        when(this.resolver.getUserID()).thenReturn(ME);
        this.pathInfo = mock(RequestPathInfo.class);
        this.request = mock(SlingJakartaHttpServletRequest.class);
        when(this.request.getResourceResolver()).thenReturn(this.resolver);
        when(this.request.getRequestPathInfo()).thenReturn(this.pathInfo);
        when(this.request.getParameterMap()).thenReturn(Map.of());
        this.response = mock(SlingJakartaHttpServletResponse.class);
        this.body = new StringWriter();
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
    }

    private ProfileProjection projection()
    {
        return new ProfileProjection(ME, null, Set.of(ME), List.of(), "{\"account\":\"jdoe\"}");
    }

    /** A session that can answer what principals somebody holds. */
    private void jackrabbitSession(final String... groups) throws RepositoryException
    {
        final JackrabbitSession session = mock(JackrabbitSession.class);
        final UserManager users = mock(UserManager.class);
        final Authorizable account = mock(Authorizable.class);
        final Principal own = mock(Principal.class);
        when(own.getName()).thenReturn(ME);
        when(account.getPrincipal()).thenReturn(own);
        when(users.getAuthorizable(ME)).thenReturn(account);
        when(session.getUserManager()).thenReturn(users);
        final PrincipalManager principals = mock(PrincipalManager.class);
        final PrincipalIterator held = mock(PrincipalIterator.class);
        final Boolean[] more = new Boolean[groups.length + 1];
        for (int i = 0; i < groups.length; i++) {
            more[i] = true;
        }
        more[groups.length] = false;
        when(held.hasNext()).thenReturn(more[0], java.util.Arrays.copyOfRange(more, 1, more.length));
        if (groups.length > 0) {
            final Principal[] asPrincipals = new Principal[groups.length];
            for (int i = 0; i < groups.length; i++) {
                final Principal group = mock(Principal.class);
                when(group.getName()).thenReturn(groups[i]);
                asPrincipals[i] = group;
            }
            when(held.nextPrincipal()).thenReturn(asPrincipals[0],
                java.util.Arrays.copyOfRange(asPrincipals, 1, asPrincipals.length));
        }
        when(principals.getGroupMembership(own)).thenReturn(held);
        when(session.getPrincipalManager()).thenReturn(principals);
        when(this.resolver.adaptTo(Session.class)).thenReturn(session);
    }

    private Requester captureRequester()
    {
        final ArgumentCaptor<Requester> requester = ArgumentCaptor.forClass(Requester.class);
        verify(this.profiles).read(any(), requester.capture());
        return requester.getValue();
    }

    @Test
    void servesTheRequestersOwnProfileWhenNoAccountIsNamed() throws Exception
    {
        when(this.profiles.read(eq(ME), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
        verify(this.response).setContentType("application/json;charset=UTF-8");
        assertEquals("{\"account\":\"jdoe\"}", this.body.toString());
    }

    @Test
    void servesTheAccountNamedBySuffix() throws Exception
    {
        when(this.pathInfo.getSuffix()).thenReturn("/asmith");
        when(this.profiles.read(eq("asmith"), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
    }

    @Test
    void treatsABareSlashSuffixAsNoAccountAtAll() throws Exception
    {
        when(this.pathInfo.getSuffix()).thenReturn("/");
        when(this.profiles.read(eq(ME), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
    }

    @Test
    void acceptsASuffixWithoutALeadingSlash() throws Exception
    {
        when(this.pathInfo.getSuffix()).thenReturn("asmith");
        when(this.profiles.read(eq("asmith"), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
    }

    @Test
    void answersNotFoundForAnAccountThatDoesNotExist() throws Exception
    {
        when(this.profiles.read(any(), any())).thenReturn(Optional.empty());

        this.servlet.doGet(this.request, this.response);

        verify(this.response).sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void reportsOnlyTheRequestersOwnNameWhenTheSessionIsNotJackrabbits() throws Exception
    {
        when(this.resolver.adaptTo(Session.class)).thenReturn(mock(Session.class));
        when(this.profiles.read(any(), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        assertEquals(Set.of(ME), captureRequester().getPrincipalNames());
    }

    @Test
    void reportsEveryPrincipalTheRequesterHolds() throws Exception
    {
        jackrabbitSession("iap-user-administrators", "reviewer");
        when(this.profiles.read(any(), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        assertEquals(Set.of(ME, "iap-user-administrators", "reviewer"),
            captureRequester().getPrincipalNames());
    }

    @Test
    void failsClosedWhenThePrincipalsCannotBeRead() throws Exception
    {
        final JackrabbitSession session = mock(JackrabbitSession.class);
        when(session.getUserManager()).thenThrow(new RepositoryException("no"));
        when(this.resolver.adaptTo(Session.class)).thenReturn(session);
        when(this.profiles.read(any(), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        // Without the groups the requester holds nothing but their own name, so they can still act on their own
        // profile and on nobody else's
        assertEquals(Set.of(ME), captureRequester().getPrincipalNames());
    }

    @Test
    void survivesAnAccountTheSessionWillNotResolve() throws Exception
    {
        final JackrabbitSession session = mock(JackrabbitSession.class);
        final UserManager users = mock(UserManager.class);
        when(users.getAuthorizable(ME)).thenReturn(null);
        when(session.getUserManager()).thenReturn(users);
        when(this.resolver.adaptTo(Session.class)).thenReturn(session);
        when(this.profiles.read(any(), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        assertEquals(Set.of(ME), captureRequester().getPrincipalNames());
    }

    @Test
    void usesAnEmptyNameWhenTheResolverWillNotSayWhoItIs() throws Exception
    {
        when(this.resolver.getUserID()).thenReturn(null);
        when(this.profiles.read(eq(""), any())).thenReturn(Optional.of(projection()));

        this.servlet.doGet(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
    }

    @Test
    void hasAConstructorForOsgiToInjectInto()
    {
        // OSGi builds it with no arguments and injects the service into the field
        assertEquals(UserProfileServlet.class, new UserProfileServlet().getClass());
    }

    @Test
    void acceptsAChange() throws Exception
    {
        when(this.profiles.update(eq(ME), any(), any()))
            .thenReturn(Optional.of(new UpdateOutcome(List.of("email"), Map.of())));

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_OK);
        assertTrue(this.body.toString().contains("\"status\":\"success\""));
    }

    @Test
    void leavesOutTheParametersThatAreTheRequestsOwn() throws Exception
    {
        final Map<String, String[]> posted = new java.util.HashMap<>();
        posted.put("email", new String[] { "jdoe@example.org" });
        posted.put("_charset_", new String[] { "UTF-8" });
        posted.put(":operation", new String[] { "import" });
        when(this.request.getParameterMap()).thenReturn(posted);
        when(this.profiles.update(any(), any(), any()))
            .thenReturn(Optional.of(new UpdateOutcome(List.of("email"), Map.of())));

        this.servlet.doPost(this.request, this.response);

        // Everything named here is refused whole when one field is unknown, so a form's own parameters would
        // otherwise turn every save into a refusal
        final ArgumentCaptor<Map<String, String[]>> asked = ArgumentCaptor.captor();
        verify(this.profiles).update(any(), any(), asked.capture());
        assertEquals(Set.of("email"), asked.getValue().keySet());
    }

    @Test
    void answersBadRequestWhenSomethingWasRefused() throws Exception
    {
        when(this.profiles.update(any(), any(), any()))
            .thenReturn(Optional.of(new UpdateOutcome(List.of(), Map.of("email", "is not in the expected format"))));

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
        assertTrue(this.body.toString().contains("is not in the expected format"));
    }

    @Test
    void answersForbiddenWhenTheProfileIsNotTheirsToChange() throws Exception
    {
        when(this.profiles.update(any(), any(), any()))
            .thenReturn(Optional.of(UpdateOutcome.forbidden("this is not your profile to change")));

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void answersNotFoundWhenChangingAnAccountThatDoesNotExist() throws Exception
    {
        when(this.profiles.update(any(), any(), any())).thenReturn(Optional.empty());

        this.servlet.doPost(this.request, this.response);

        verify(this.response).sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
    }
}
