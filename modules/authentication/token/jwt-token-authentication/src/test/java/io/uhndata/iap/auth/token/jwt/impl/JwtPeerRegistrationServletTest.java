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
package io.uhndata.iap.auth.token.jwt.impl;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.Date;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Session;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JwtPeerRegistrationServlet}, the administrator-only way to trust another instance.
 *
 * <p>What matters is that nobody but an administrator can add a trust anchor, that a key is stored under the
 * fingerprint an inbound token's {@code kid} will actually carry, and that an existing peer is never silently
 * replaced.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class JwtPeerRegistrationServletTest
{
    private static final String SELF_ID = "https://iap.example.org";

    private static final String PEER_ISSUER = "https://peer.example.org";

    @Mock
    private ResourceResolverFactory resolverFactory;

    @Mock
    private ResourceResolver serviceResolver;

    @Mock
    private ResourceResolver callerResolver;

    @Mock
    private Session callerSession;

    @Mock
    private Resource keyRoot;

    @Mock
    private SlingJakartaHttpServletRequest request;

    @Mock
    private SlingJakartaHttpServletResponse response;

    private KeyPair peerPair;

    private StringWriter body;

    private JwtPeerRegistrationServlet servlet;

    @BeforeEach
    public void setUp() throws Exception
    {
        this.peerPair = Jwts.SIG.RS256.keyPair().build();
        this.body = new StringWriter();
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));

        when(this.request.getResourceResolver()).thenReturn(this.callerResolver);
        when(this.callerResolver.adaptTo(Session.class)).thenReturn(this.callerSession);
        when(this.callerSession.getUserID()).thenReturn("admin");

        when(this.resolverFactory.getServiceResourceResolver(any())).thenReturn(this.serviceResolver);
        when(this.serviceResolver.getResource(IapJwtTokenManagerImpl.KEY_ROOT)).thenReturn(this.keyRoot);

        this.servlet = new JwtPeerRegistrationServlet(this.resolverFactory);
    }

    @Test
    public void adminRegistersAPeerUnderTheFingerprintTokensWillCarry() throws Exception
    {
        post(validBody());

        this.servlet.doPost(this.request, this.response);

        final String expectedKid = IapJwtTokenManagerImpl.getFingerprint(this.peerPair.getPublic());
        final ArgumentCaptor<Map<String, Object>> stored = captor();
        verify(this.serviceResolver).create(eq(this.keyRoot), eq(expectedKid), stored.capture());
        verify(this.serviceResolver).commit();
        verify(this.response).setStatus(HttpServletResponse.SC_CREATED);

        Assertions.assertEquals(PEER_ISSUER, stored.getValue().get(IapJwtTokenManagerImpl.ISSUER_PROP));
        Assertions.assertEquals(Encoders.BASE64.encode(this.peerPair.getPublic().getEncoded()),
            stored.getValue().get(IapJwtTokenManagerImpl.VERIFY_PROP));
        Assertions.assertTrue(this.body.toString().contains(expectedKid), this.body.toString());
    }

    @Test
    public void aPeerRegisteredHereIsThenTrustedByTheTokenManager() throws Exception
    {
        // The registration and the verification have to agree about the fingerprint, or a peer can be added and
        // still have every one of its tokens rejected. Rather than assert that twice, feed what the servlet
        // stored straight into the locator's lookup and present a token from that peer.
        post(validBody());
        this.servlet.doPost(this.request, this.response);

        final ArgumentCaptor<Map<String, Object>> stored = captor();
        final ArgumentCaptor<String> nodeName = ArgumentCaptor.forClass(String.class);
        verify(this.serviceResolver).create(any(), nodeName.capture(), stored.capture());

        final IapJwtTokenManagerImpl manager = managerTrusting(nodeName.getValue(), stored.getValue());
        final String peerToken = Jwts.builder()
            .issuer(PEER_ISSUER)
            .audience().add(SELF_ID).and()
            .subject("peer-service")
            .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
            .header().keyId(nodeName.getValue()).and()
            .signWith(this.peerPair.getPrivate())
            .compact();

        Assertions.assertNotNull(manager.parse(peerToken),
            "A peer registered through the endpoint must then have its tokens accepted");
    }

    @Test
    public void aPemArmouredKeyIsAccepted() throws Exception
    {
        final String pem = "-----BEGIN PUBLIC KEY-----\n"
            + Encoders.BASE64.encode(this.peerPair.getPublic().getEncoded()).replaceAll("(.{64})", "$1\n")
            + "\n-----END PUBLIC KEY-----\n";
        post("{\"issuer\": \"" + PEER_ISSUER + "\", \"key\": \"" + pem.replace("\n", "\\n") + "\"}");

        this.servlet.doPost(this.request, this.response);

        // Stored under the same fingerprint as the unarmoured form, since the key is re-encoded before hashing
        verify(this.serviceResolver).create(any(),
            eq(IapJwtTokenManagerImpl.getFingerprint(this.peerPair.getPublic())), any());
        verify(this.response).setStatus(HttpServletResponse.SC_CREATED);
    }

    @Test
    public void aNonAdminIsRefusedWithoutReadingTheBody() throws Exception
    {
        when(this.callerSession.getUserID()).thenReturn("someone");
        post(validBody());

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(this.request, never()).getReader();
        verifyNothingWasStored();
    }

    @Test
    public void aKeyThatIsNotAKeyIsRefused() throws Exception
    {
        post("{\"issuer\": \"" + PEER_ISSUER + "\", \"key\": \"bm90IGEga2V5\"}");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasStored();
    }

    @Test
    public void aKeyThatIsNotBase64IsRefused() throws Exception
    {
        post("{\"issuer\": \"" + PEER_ISSUER + "\", \"key\": \"!!! not base64 !!!\"}");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasStored();
    }

    @Test
    public void anIssuerThatCouldNeverMatchIsRefused() throws Exception
    {
        // JSON-escaped: the fourth is a real line break, the kind that would forge a log line
        final String[] issuers = {"", "   ", " https://peer.example.org", "https://peer.example.org\\n",
            "a/b:c", "https://exa mple.org", "x".repeat(2049)};
        for (final String issuer : issuers) {
            this.body.getBuffer().setLength(0);
            post("{\"issuer\": \"" + issuer + "\", \"key\": \"" + encodedPeerKey() + "\"}");

            this.servlet.doPost(this.request, this.response);

            Assertions.assertTrue(this.body.toString().contains("error"), "Accepted the issuer '" + issuer + "'");
        }
        verifyNothingWasStored();
    }

    @Test
    public void uriAndPlainIssuersAreStoredExactlyAsGiven() throws Exception
    {
        final String[] issuers = {"https://peer.example.org", "https://peer.example.org:8443/iap/",
            "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66", "peerexample8080"};
        for (final String issuer : issuers) {
            post("{\"issuer\": \"" + issuer + "\", \"key\": \"" + encodedPeerKey() + "\"}");
            this.servlet.doPost(this.request, this.response);
        }

        // Unnormalized, since the comparison against the token's claim is too
        final ArgumentCaptor<Map<String, Object>> stored = captor();
        verify(this.serviceResolver, times(issuers.length)).create(any(), anyString(),
            stored.capture());
        for (int i = 0; i < issuers.length; ++i) {
            Assertions.assertEquals(issuers[i], stored.getAllValues().get(i).get(IapJwtTokenManagerImpl.ISSUER_PROP));
        }
    }

    @Test
    public void aMissingFieldIsRefused() throws Exception
    {
        post("{\"issuer\": \"" + PEER_ISSUER + "\"}");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasStored();
    }

    @Test
    public void aBodyThatIsNotAJsonObjectIsRefused() throws Exception
    {
        post("not json at all");

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNothingWasStored();
    }

    @Test
    public void anAlreadyRegisteredKeyIsNotSilentlyReplaced() throws Exception
    {
        when(this.serviceResolver.getResource(IapJwtTokenManagerImpl.KEY_ROOT + "/"
            + IapJwtTokenManagerImpl.getFingerprint(this.peerPair.getPublic()))).thenReturn(this.keyRoot);
        post(validBody());

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_CONFLICT);
        verifyNothingWasStored();
    }

    @Test
    public void aMissingServiceUserIsReportedAsAServerFault() throws Exception
    {
        when(this.resolverFactory.getServiceResourceResolver(any()))
            .thenThrow(new LoginException("No such service user"));
        post(validBody());

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    public void aMissingKeyRootIsReportedAsAServerFault() throws Exception
    {
        when(this.serviceResolver.getResource(IapJwtTokenManagerImpl.KEY_ROOT)).thenReturn(null);
        post(validBody());

        this.servlet.doPost(this.request, this.response);

        verify(this.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verifyNothingWasStored();
    }

    /**
     * A token manager whose peer lookup finds exactly the node the servlet just wrote.
     *
     * @param fingerprint the node name the servlet chose
     * @param properties the properties the servlet stored
     * @return an activated manager, with its own freshly generated signing key
     * @throws Exception when the mocked repository cannot be set up
     */
    private IapJwtTokenManagerImpl managerTrusting(final String fingerprint, final Map<String, Object> properties)
        throws Exception
    {
        final KeyPair ownPair = Jwts.SIG.RS256.keyPair().build();
        final Resource own = keyNodeResource(Map.of(
            IapJwtTokenManagerImpl.SIGNING_KEY_PROP, Encoders.BASE64.encode(ownPair.getPrivate().getEncoded()),
            IapJwtTokenManagerImpl.VERIFY_PROP, Encoders.BASE64.encode(ownPair.getPublic().getEncoded())));
        final Resource peer = keyNodeResource(properties);

        final ResourceResolverFactory factory = mock(ResourceResolverFactory.class);
        final ResourceResolver resolver = mock(ResourceResolver.class);
        doReturn(resolver).when(factory).getServiceResourceResolver(any());
        doAnswer(invocation -> new NonExistingResource(resolver, invocation.getArgument(0)))
            .when(resolver).resolve(anyString());
        doReturn(own).when(resolver).resolve(IapJwtTokenManagerImpl.KEY_PATH);
        doReturn(peer).when(resolver).resolve(IapJwtTokenManagerImpl.KEY_ROOT + "/" + fingerprint);
        return new IapJwtTokenManagerImpl(factory, IapJwtTokenManagerImplTest.configWithIdentity(SELF_ID));
    }

    /**
     * A resource whose JCR node answers with the given string properties.
     *
     * @param properties the properties the node should carry
     * @return the mocked resource
     * @throws Exception when the mocks cannot be set up
     */
    private Resource keyNodeResource(final Map<String, Object> properties) throws Exception
    {
        final Resource resource = mock(Resource.class);
        final Node node = mock(Node.class);
        doReturn(node).when(resource).adaptTo(Node.class);
        for (final Map.Entry<String, Object> property : properties.entrySet()) {
            final Property value = mock(Property.class);
            doReturn(String.valueOf(property.getValue())).when(value).getString();
            doReturn(true).when(node).hasProperty(property.getKey());
            doReturn(value).when(node).getProperty(property.getKey());
        }
        return resource;
    }

    private String encodedPeerKey()
    {
        return Encoders.BASE64.encode(this.peerPair.getPublic().getEncoded());
    }

    private String validBody()
    {
        return "{\"issuer\": \"" + PEER_ISSUER + "\", \"key\": \"" + encodedPeerKey() + "\"}";
    }

    /**
     * Point the mocked request at a body.
     *
     * @param requestBody the raw request body
     * @throws Exception when the mocks cannot be set up
     */
    private void post(final String requestBody) throws Exception
    {
        when(this.request.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
    }

    /**
     * Assert that no peer was written.
     *
     * @throws Exception when the mocks cannot be inspected
     */
    private void verifyNothingWasStored() throws Exception
    {
        verify(this.serviceResolver, never()).create(any(), anyString(), any());
        verify(this.serviceResolver, never()).commit();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor()
    {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }
}
