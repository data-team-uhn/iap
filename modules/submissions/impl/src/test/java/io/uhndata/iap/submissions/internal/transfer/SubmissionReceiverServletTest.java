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
package io.uhndata.iap.submissions.internal.transfer;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.request.RequestParameterMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.workflows.api.EventAttachment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SubmissionReceiverServlet}: what it takes as proof of who is sending, and what it hands on.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SubmissionReceiverServletTest
{
    private static final String ORIGIN = "https://frontdoor.example.org";

    private final TokenManager tokens = Mockito.mock(TokenManager.class);

    private final SubmissionReceiver receiver = Mockito.mock(SubmissionReceiver.class);

    private final SubmissionReceiverServlet servlet = new SubmissionReceiverServlet(this.tokens, this.receiver);

    private final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);

    private final SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);

    private final IapToken token = Mockito.mock(IapToken.class);

    private final RequestParameter manifest = Mockito.mock(RequestParameter.class);

    private final RequestParameter scan = Mockito.mock(RequestParameter.class);

    private final StringWriter body = new StringWriter();

    @BeforeEach
    void setUp() throws Exception
    {
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
        Mockito.when(this.request.getHeader("Authorization")).thenReturn("Bearer signed");
        Mockito.when(this.tokens.parse("signed")).thenReturn(this.token);
        Mockito.when(this.token.getIssuer()).thenReturn(ORIGIN);
        Mockito.when(this.token.getClaim("email")).thenReturn("alice@example.org");
        Mockito.when(this.manifest.isFormField()).thenReturn(true);
        Mockito.when(this.manifest.getString("UTF-8")).thenReturn("{\"version\": 1}");
        Mockito.when(this.scan.isFormField()).thenReturn(false);
        final RequestParameter[] both = {this.manifest, this.manifest};
        final Map<String, RequestParameter[]> parameters = new LinkedHashMap<>();
        parameters.put("manifest", new RequestParameter[] {this.manifest});
        parameters.put("b0", new RequestParameter[] {this.scan});
        // Neither a single file nor a manifest: carried by nothing
        parameters.put("repeated", both);
        final RequestParameterMap map = Mockito.mock(RequestParameterMap.class);
        Mockito.when(map.entrySet()).thenReturn(parameters.entrySet());
        Mockito.when(this.request.getRequestParameterMap()).thenReturn(map);
        Mockito.when(this.request.getRequestParameter("manifest")).thenReturn(this.manifest);
        Mockito.when(this.receiver.receive(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(ReceiveOutcome.created("/Submissions/ab/cd/s1"));
    }

    @Test
    void handsOnWhatTheTokenSaysAndWhatTheRequestCarries() throws Exception
    {
        this.servlet.doPost(this.request, this.response);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, EventAttachment>> files = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(this.receiver).receive(Mockito.eq(ORIGIN), Mockito.eq("alice@example.org"),
            Mockito.eq("{\"version\": 1}"), files.capture());
        assertEquals(Set.of("b0"), files.getValue().keySet());
        Mockito.verify(this.response).setStatus(201);
        final JsonObject answer = Json.createReader(new StringReader(this.body.toString())).readObject();
        assertEquals("created", answer.getString("status"));
        assertEquals("/Submissions/ab/cd/s1", answer.getString("path"));
    }

    @Test
    void aBareTokenIsAcceptedToo() throws Exception
    {
        Mockito.when(this.request.getHeader("Authorization")).thenReturn("signed");

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(201);
    }

    @Test
    void noTokenIsNoEntry() throws Exception
    {
        Mockito.when(this.request.getHeader("Authorization")).thenReturn(null);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(401);
        Mockito.verifyNoInteractions(this.receiver);
    }

    @Test
    void aTokenThatDoesNotVerifyIsNoEntry() throws Exception
    {
        Mockito.when(this.tokens.parse("signed")).thenReturn(null);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(401);
    }

    @Test
    void anExpiredTokenIsNoEntry() throws Exception
    {
        Mockito.when(this.token.isExpired(Mockito.anyLong())).thenReturn(true);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(401);
    }

    @Test
    void aTokenNamingNoSubmitterIsRefused() throws Exception
    {
        Mockito.when(this.token.getClaim("email")).thenReturn(null);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(400);
        Mockito.verifyNoInteractions(this.receiver);
    }

    @Test
    void aManifestIsRequired() throws Exception
    {
        Mockito.when(this.request.getRequestParameter("manifest")).thenReturn(null);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(400);
    }

    @Test
    void aManifestSentAsAFileIsRefused() throws Exception
    {
        Mockito.when(this.manifest.isFormField()).thenReturn(false);

        this.servlet.doPost(this.request, this.response);

        Mockito.verify(this.response).setStatus(400);
        Mockito.verifyNoInteractions(this.receiver);
    }
}
