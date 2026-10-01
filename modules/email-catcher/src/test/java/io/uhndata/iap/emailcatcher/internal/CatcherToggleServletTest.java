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
package io.uhndata.iap.emailcatcher.internal;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.uhndata.iap.principals.api.PrincipalService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching the catcher on and off writes the one setting that already governs it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CatcherToggleServletTest
{
    private static final String HOME = CaughtMailService.CAUGHT_MAIL_PATH;

    private final SlingContext context = new SlingContext();

    private final CatcherToggleServlet servlet = new CatcherToggleServlet();

    private Configuration configuration;

    private PrincipalService principals;

    /** What the caller's resolver adapts to, so that a test can decide what the user store says. */
    private Session session;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(HOME, "sling:resourceType", "mail/CaughtMailHomepage");

        this.configuration = Mockito.mock(Configuration.class);
        Mockito.when(this.configuration.getProperties()).thenReturn(null);
        final ConfigurationAdmin admin = Mockito.mock(ConfigurationAdmin.class);
        Mockito.when(admin.getConfiguration(CatcherToggleServlet.PID, null))
            .thenReturn(this.configuration);
        inject("configurationAdmin", admin);

        this.principals = Mockito.mock(PrincipalService.class);
        Mockito.when(this.principals.isOneOf(Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn(true);
        inject("principals", this.principals);
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = CatcherToggleServlet.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.servlet, value);
    }

    /**
     * A resolver that knows who is asking.
     *
     * <p>
     * The mock resource resolver answers {@code null} for its user id, and the servlet reads the
     * caller through {@link io.uhndata.iap.utils.UserIds#canonical} -- which falls back on exactly
     * that when there is no JCR session. With no name the guard refuses, correctly, and every test
     * here would be asserting that refusal rather than what it says it asserts.
     * </p>
     *
     * @return the context's resolver, answering for somebody
     */
    private ResourceResolver asSomebody()
    {
        final ResourceResolver resolver = Mockito.spy(this.context.resourceResolver());
        Mockito.doReturn("an-administrator").when(resolver).getUserID();
        Mockito.doReturn(this.session).when(resolver).adaptTo(Session.class);
        return resolver;
    }

    /**
     * Make the user store answer for the caller.
     *
     * @param authorizable what {@code getAuthorizable} returns for them, {@code null} for nobody
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private void userStoreReturns(final Authorizable authorizable) throws RepositoryException
    {
        final UserManager users = Mockito.mock(UserManager.class);
        Mockito.when(users.getAuthorizable("an-administrator")).thenReturn(authorizable);
        this.session = sessionFor(users);
    }

    /**
     * A session that answers for the caller and hands out the given user store.
     *
     * @param users the user store, or {@code null} to leave {@code getUserManager} unstubbed
     * @return the session the caller's resolver will adapt to
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static JackrabbitSession sessionFor(final UserManager users) throws RepositoryException
    {
        final JackrabbitSession jackrabbit = Mockito.mock(JackrabbitSession.class);
        // UserIds.canonical prefers the session's id over the resolver's, so a session that does not
        // answer for one leaves the servlet with no caller and it refuses before reaching anything
        // this test is about.
        Mockito.when(jackrabbit.getUserID()).thenReturn("an-administrator");
        if (users != null) {
            Mockito.when(jackrabbit.getUserManager()).thenReturn(users);
        }
        return jackrabbit;
    }

    private MockSlingJakartaHttpServletResponse post(final Map<String, Object> parameters)
        throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(asSomebody(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        request.setParameterMap(parameters);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        return response;
    }

    private JsonObject bodyOf(final MockSlingJakartaHttpServletResponse response)
    {
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }

    @SuppressWarnings("unchecked")
    private Dictionary<String, Object> written() throws IOException
    {
        final ArgumentCaptor<Dictionary<String, Object>> captor =
            ArgumentCaptor.forClass(Dictionary.class);
        Mockito.verify(this.configuration).update(captor.capture());
        return captor.getValue();
    }

    @Test
    void switchesCatchingOn() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(200, response.getStatus());
        assertEquals(Boolean.TRUE, written().get(CatcherToggleServlet.ENABLED));
        assertTrue(bodyOf(response).getBoolean("requested"));
    }

    @Test
    void switchesCatchingOff() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "false"));

        assertEquals(200, response.getStatus());
        assertEquals(Boolean.FALSE, written().get(CatcherToggleServlet.ENABLED));
        assertFalse(bodyOf(response).getBoolean("requested"));
    }

    @Test
    void keepsEveryOtherPropertyOfTheConfiguration() throws IOException
    {
        // Whatever else the configuration carries belongs to whoever put it there, and a toggle that
        // replaced the dictionary would quietly drop it.
        final Dictionary<String, Object> existing = new Hashtable<>();
        existing.put("service.pid", CatcherToggleServlet.PID);
        Mockito.when(this.configuration.getProperties()).thenReturn(existing);

        post(Map.of("enabled", "true"));

        assertEquals(CatcherToggleServlet.PID, written().get("service.pid"));
        assertEquals(Boolean.TRUE, written().get(CatcherToggleServlet.ENABLED));
    }

    @Test
    void refusesSomebodyWhoIsNotAnAdministrator() throws IOException
    {
        Mockito.when(this.principals.isOneOf(Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn(false);

        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(403, response.getStatus());
        Mockito.verifyNoInteractions(this.configuration);
    }

    @Test
    void refusesARequestThatCarriesNoIdentityAtAll() throws IOException
    {
        // Fail closed without asking: a principal service answering for a nameless caller would be
        // answering about nobody, and "is nobody an administrator" is not a question worth a lookup.
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(),
                this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        request.setParameterMap(Map.of("enabled", "true"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(403, response.getStatus());
        Mockito.verifyNoInteractions(this.principals);
        Mockito.verifyNoInteractions(this.configuration);
    }

    @Test
    void asksTheAdministratorsGroup() throws IOException
    {
        post(Map.of("enabled", "true"));

        Mockito.verify(this.principals).isOneOf(Mockito.anyString(),
            Mockito.eq(java.util.List.of(CatcherToggleServlet.ADMINISTRATORS)), Mockito.any());
    }

    @Test
    void refusesAValueThatIsNeitherTrueNorFalse() throws IOException
    {
        // Boolean.parseBoolean answers false for anything it does not recognise, so "yes" would
        // otherwise switch catching off while reading as a request to switch it on.
        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "yes"));

        assertEquals(400, response.getStatus());
        Mockito.verifyNoInteractions(this.configuration);
    }

    @Test
    void refusesAMissingValue() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = post(Map.of());

        assertEquals(400, response.getStatus());
        Mockito.verifyNoInteractions(this.configuration);
    }

    @Test
    void reportsAFailureToWriteRatherThanClaimingSuccess() throws IOException
    {
        Mockito.doThrow(new IOException("no configuration store"))
            .when(this.configuration).update(Mockito.any());

        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(500, response.getStatus());
    }

    @Test
    void admitsTheRepositorySuperuserWhoBelongsToNoGroup() throws Exception
    {
        // `iap-administrators` ships empty, so on a fresh instance the superuser is the only account
        // there is. They bypass access control anyway and can write this setting from the Felix
        // console, so refusing them here would guard nothing and lock out the one available operator.
        final User superuser = Mockito.mock(User.class);
        Mockito.when(superuser.isAdmin()).thenReturn(true);
        userStoreReturns(superuser);
        Mockito.when(this.principals.isOneOf(Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn(false);

        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(200, response.getStatus());
        assertEquals(Boolean.TRUE, written().get(CatcherToggleServlet.ENABLED));
    }

    @Test
    void anOrdinaryUserIsStillJudgedByTheirGroups() throws Exception
    {
        final User ordinary = Mockito.mock(User.class);
        Mockito.when(ordinary.isAdmin()).thenReturn(false);
        userStoreReturns(ordinary);
        Mockito.when(this.principals.isOneOf(Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn(false);

        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(403, response.getStatus());
        Mockito.verify(this.configuration, Mockito.never()).update(Mockito.any());
    }

    @Test
    void aNameTheUserStoreDoesNotKnowIsNotTheSuperuser() throws Exception
    {
        userStoreReturns(null);
        Mockito.when(this.principals.isOneOf(Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn(false);

        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(403, response.getStatus());
    }

    @Test
    void anUnreadableUserStoreLeavesTheGroupCheckToAnswer() throws Exception
    {
        final JackrabbitSession jackrabbit = sessionFor(null);
        Mockito.when(jackrabbit.getUserManager()).thenThrow(new RepositoryException("no store"));
        this.session = jackrabbit;

        // Still allowed, because the group check is stubbed to admit them: the superuser test failing
        // to run is not itself a refusal.
        final MockSlingJakartaHttpServletResponse response = post(Map.of("enabled", "true"));

        assertEquals(200, response.getStatus());
    }
}
