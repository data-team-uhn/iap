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
package io.uhndata.iap.remoterequests.internal;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RemoteNodeServlet}, the token-authenticated endpoint a remote instance posts to.
 *
 * <p>The two things worth pinning down are that nothing is written without a token that verifies, and that a
 * name the peer chose cannot address anything outside {@code /remote}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RemoteNodeServletTest
{
    /** A token string the mocked manager accepts. */
    private static final String VALID_TOKEN = "a.valid.token";

    /** The node name the happy-path tests ask for, and the body asking for it. */
    private static final String NAME = "abc123";

    private static final String VALID_BODY = "{\"path\": \"" + NAME + "\"}";

    @Mock
    private TokenManager tokenManager;

    @Mock
    private ResourceResolverFactory resolverFactory;

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Resource root;

    @Mock
    private IapToken token;

    @Mock
    private SlingJakartaHttpServletRequest request;

    @Mock
    private SlingJakartaHttpServletResponse response;

    private StringWriter body;

    private RemoteNodeServlet servlet;

    @BeforeEach
    public void setUp() throws Exception
    {
        this.body = new StringWriter();
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));

        when(this.token.getUserId()).thenReturn("peer");
        when(this.token.isExpired(anyLong())).thenReturn(false);
        when(this.tokenManager.parse(VALID_TOKEN)).thenReturn(this.token);

        when(this.resolverFactory.getServiceResourceResolver(any())).thenReturn(this.resolver);
        when(this.resolver.getResource(RemoteNodeServlet.ROOT)).thenReturn(this.root);

        this.servlet = new RemoteNodeServlet(this.tokenManager, this.resolverFactory);
    }

    @Test
    public void validRequestCreatesTheNodeUnderRemote() throws Exception
    {
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.resolver).create(eq(this.root), eq(NAME), any());
        verify(this.resolver).commit();
        verify(this.response).setStatus(HttpServletResponse.SC_CREATED);
        Assertions.assertTrue(this.body.toString().contains(RemoteNodeServlet.ROOT + "/" + NAME),
            this.body.toString());
    }

    @Test
    public void bearerSchemeIsAccepted() throws Exception
    {
        request("Bearer " + VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_CREATED);
    }

    @Test
    public void missingHeaderIsRejectedWithoutTouchingTheRepository() throws Exception
    {
        request(null, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNothingWasWritten();
    }

    @Test
    public void unverifiableTokenIsRejected() throws Exception
    {
        request("not.a.token", VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNothingWasWritten();
    }

    @Test
    public void expiredTokenIsRejected() throws Exception
    {
        when(this.token.isExpired(anyLong())).thenReturn(true);
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNothingWasWritten();
    }

    @Test
    public void aNameThatLeavesTheSubtreeIsRejected() throws Exception
    {
        final String[] names = {"..", "../../etc", "a/b", "a.txt", "jcr:content", "", "a_b", "a b", "/absolute"};
        for (final String name : names) {
            this.body.getBuffer().setLength(0);
            request(VALID_TOKEN, "{\"path\": \"" + name + "\"}");

            this.servlet.doPost(this.request, this.response);

            Assertions.assertTrue(this.body.toString().contains("error"), "Accepted the name '" + name + "'");
        }
        verify(this.response, never()).setStatus(HttpServletResponse.SC_CREATED);
        verifyNothingWasWritten();
    }

    @Test
    public void aBodyWithoutAPathIsRejected() throws Exception
    {
        request(VALID_TOKEN, "{\"other\": \"x\"}");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasWritten();
    }

    @Test
    public void aBodyThatIsNotAJsonObjectIsRejected() throws Exception
    {
        request(VALID_TOKEN, "not json at all");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasWritten();
    }

    @Test
    public void anExistingNodeIsNotOverwritten() throws Exception
    {
        when(this.resolver.getResource(RemoteNodeServlet.ROOT + "/" + NAME)).thenReturn(this.root);
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_CONFLICT);
        verifyNothingWasWritten();
    }

    @Test
    public void aMissingRemoteRootIsReportedAsAServerFault() throws Exception
    {
        when(this.resolver.getResource(RemoteNodeServlet.ROOT)).thenReturn(null);
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verifyNothingWasWritten();
    }

    @Test
    public void aMissingServiceUserIsReportedAsAServerFault() throws Exception
    {
        when(this.resolverFactory.getServiceResourceResolver(any()))
            .thenThrow(new LoginException("No such service user"));
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verifyNothingWasWritten();
    }

    @Test
    public void aFailedWriteIsReportedAsAServerFault() throws Exception
    {
        when(this.resolver.create(any(), anyString(), any()))
            .thenThrow(new PersistenceException("Read-only"));
        request(VALID_TOKEN, VALID_BODY);

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(this.resolver, never()).commit();
    }

    /**
     * Point the mocked request at a token and a body.
     *
     * @param authorization the value of the Authorization header, or {@code null} for no header at all
     * @param requestBody the raw request body
     * @throws Exception when the mocks cannot be set up
     */
    private void request(final String authorization, final String requestBody) throws Exception
    {
        when(this.request.getHeader("Authorization")).thenReturn(authorization);
        when(this.request.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
    }

    /**
     * Assert that the repository was left alone.
     *
     * @throws Exception when the mocks cannot be inspected
     */
    private void verifyNothingWasWritten() throws Exception
    {
        verify(this.resolver, never()).create(any(), anyString(), any());
        verify(this.resolver, never()).commit();
    }
}
