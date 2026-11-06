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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.jcr.Node;
import javax.jcr.Property;

import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link IapJwtTokenManagerImpl}.
 *
 * <p>The repository is mocked. Its key node holds a key pair generated for each test.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(MockitoExtension.class)
public class IapJwtTokenManagerImplTest
{
    /** The one extra claim a token read back keeps. */
    private static final String SESSION_SUBJECT = "iap:sessionSubject";

    private static final String KEY_PATH = "/jcr:system/iap-jwt/JWTRSA256Key";

    private static final String SELF_ID = "https://iap.example.org";

    private static final String PEER_ID = "https://peer.example.org:8443/iap/";

    private static final String PEER_KEY_PATH_PREFIX = "/jcr:system/iap-jwt/";

    @Mock
    private ResourceResolverFactory resolverFactory;

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Resource keyResource;

    @Mock
    private Node keyNode;

    @Mock
    private Property keyProperty;

    @Mock
    private Property verifyProperty;

    @Mock
    private Resource peerResource;

    @Mock
    private Node peerNode;

    @Mock
    private Property peerVerifyProperty;

    @Mock
    private Property peerIssuerProperty;

    /** This instance's own keypair. Its public half is what an attacker is assumed to know. */
    private KeyPair keyPair;

    private IapJwtTokenManagerImpl manager;

    @BeforeEach
    public void setUp() throws Exception
    {
        this.keyPair = Jwts.SIG.RS256.keyPair().build();
        when(this.resolverFactory.getServiceResourceResolver(any())).thenReturn(this.resolver);
        // A real resolver answers a missing path with a non-existing resource, never null
        when(this.resolver.resolve(anyString()))
            .thenAnswer(invocation -> new NonExistingResource(this.resolver, invocation.getArgument(0)));
        when(this.resolver.resolve(KEY_PATH)).thenReturn(this.keyResource);
        when(this.keyResource.adaptTo(Node.class)).thenReturn(this.keyNode);
        when(this.keyNode.hasProperty("key")).thenReturn(true);
        when(this.keyNode.getProperty("key")).thenReturn(this.keyProperty);
        when(this.keyNode.hasProperty("verify")).thenReturn(true);
        when(this.keyNode.getProperty("verify")).thenReturn(this.verifyProperty);
        when(this.keyProperty.getString()).thenReturn(Encoders.BASE64.encode(this.keyPair.getPrivate().getEncoded()));
        when(this.verifyProperty.getString()).thenReturn(Encoders.BASE64.encode(this.keyPair.getPublic().getEncoded()));

        this.manager = new IapJwtTokenManagerImpl(this.resolverFactory, configWithIdentity(SELF_ID));
    }

    @Test
    public void createThenParseRoundTripsUserIdAndSubject()
    {
        final IapJwtTokenImpl created = this.manager.create("guest-patient", oneHourFromNow(),
                Map.of(SESSION_SUBJECT, "/Subjects/v1"));
        final String token = created.getToken();

        // A JWS in compact form: three BASE64URL segments separated by dots
        Assertions.assertTrue(token.matches("^[\\w-_]+\\.[\\w-_]+\\.[\\w-_]+$"),
                "Issued token is not a well-formed JWT");

        final IapJwtTokenImpl parsed = this.manager.parse(token);
        Assertions.assertNotNull(parsed, "A freshly issued token must parse back");
        Assertions.assertEquals("guest-patient", parsed.getUserId());
        Assertions.assertEquals("/Subjects/v1", parsed.getPublicAttributes().get(SESSION_SUBJECT));
    }

    @Test
    public void parseRejectsNull()
    {
        Assertions.assertNull(this.manager.parse(null));
    }

    @Test
    public void parseRejectsMalformedToken()
    {
        Assertions.assertNull(this.manager.parse("garbage"));
        Assertions.assertNull(this.manager.parse("this.is.not-a-real-jwt"));
    }

    @Test
    public void parseRejectsExpiredToken()
    {
        final Calendar past = Calendar.getInstance();
        past.add(Calendar.HOUR_OF_DAY, -1);
        final String expired = this.manager.create("guest-patient", past, Map.of(SESSION_SUBJECT, "/Subjects/v1"))
                .getToken();
        Assertions.assertNull(this.manager.parse(expired), "An expired token must not parse");
    }

    @Test
    public void parseRejectsTokenSignedWithUnknownKey()
    {
        final String foreign = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .header().keyId("attacker").and()
                .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                .compact();
        Assertions.assertNull(this.manager.parse(foreign), "A token signed with a different key must not parse");
    }

    @Test
    public void parseRejectsTokenWithNoKeyId()
    {
        final String foreign = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                .compact();
        Assertions.assertNull(this.manager.parse(foreign), "A token without a key ID must not parse");
    }

    @Test
    public void parseRejectsNonSanitizedKeyId()
    {
        final String foreign = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .header().keyId("../attacker").and()
                .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                .compact();
        Assertions.assertNull(this.manager.parse(foreign), "A token with a non-sanitized key ID must not parse");
    }

    @Test
    public void aKeyIdCannotStartALogLineOfItsOwn()
    {
        final String forging = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .header().keyId("x\nFORGED").and()
                .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                .compact();

        final String logged = loggedWhileParsing(forging);

        Assertions.assertTrue(logged.contains("JWT refused"), logged);
        Assertions.assertFalse(logged.contains("\nFORGED"), logged);
    }

    @Test
    public void aHeaderValueQuotedInARefusalCannotStartALogLineOfItsOwn()
    {
        final String logged = loggedWhileParsing(signingInput("RS256\\nFORGED") + ".c2lnbmF0dXJl");

        Assertions.assertTrue(logged.contains("?FORGED"), logged);
        Assertions.assertFalse(logged.contains("\nFORGED"), logged);
    }

    @Test
    public void createThenParseRejectsInvalidAudience()
    {
        Set<String> fakeAudience = new HashSet<String>();
        fakeAudience.add("not_you");
        final IapJwtTokenImpl created = this.manager.create("guest-patient", oneHourFromNow(),
                Map.of(SESSION_SUBJECT, "/Subjects/v1"), fakeAudience);
        final String token = created.getToken();

        Assertions.assertTrue(token.matches("^[\\w-_]+\\.[\\w-_]+\\.[\\w-_]+$"),
                "Issued token is not a well-formed JWT");
        Assertions.assertNull(this.manager.parse(token),
                "A token signed with an audience that does not include us must not parse");
    }

    @Test
    public void createForeignKeyThenAccept() throws Exception
    {
        final KeyPair peerPair = Jwts.SIG.RS256.keyPair().build();
        final String peerFingerprint = IapJwtTokenManagerImpl.getFingerprint(peerPair.getPublic());
        when(this.resolver.resolve(PEER_KEY_PATH_PREFIX + peerFingerprint)).thenReturn(this.peerResource);
        when(this.peerResource.adaptTo(Node.class)).thenReturn(this.peerNode);
        when(this.peerNode.hasProperty("verify")).thenReturn(true);
        when(this.peerNode.getProperty("verify")).thenReturn(this.peerVerifyProperty);
        when(this.peerNode.getProperty("iss")).thenReturn(this.peerIssuerProperty);
        when(this.peerVerifyProperty.getString()).thenReturn(
            Encoders.BASE64.encode(peerPair.getPublic().getEncoded()));
        when(this.peerIssuerProperty.getString()).thenReturn(PEER_ID);

        final String foreign = Jwts.builder()
            .issuer(PEER_ID)
            .audience().add(SELF_ID).and()
            .expiration(oneHourFromNow().getTime())
            .header().keyId(peerFingerprint).and()
            .signWith(peerPair.getPrivate())
            .compact();
        Assertions.assertNotNull(this.manager.parse(foreign), "A foreign, trusted issued token must parse back");
    }

    @Test
    public void parseRejectsUnsecuredTokenBuiltByTheLibrary()
    {
        // Valid claims with no signature. JJWT refuses an unsecured JWT unless the parser opts in with unsecured().
        final String unsecured = Jwts.builder()
            .issuer(SELF_ID)
            .audience().add(SELF_ID).and()
            .subject("attacker")
            .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
            .header().keyId(selfFingerprint()).and()
            .compact();
        Assertions.assertNull(this.manager.parse(unsecured), "An unsecured (alg:none) token must not parse");
    }

    @Test
    public void parseRejectsHandRolledAlgNoneToken()
    {
        // The same attack written by hand: the signing input, then an empty third segment
        Assertions.assertNull(this.manager.parse(signingInput("none") + "."),
            "A hand-rolled alg:none token must not parse");
    }

    @Test
    public void parseRejectsAlgorithmConfusion() throws Exception
    {
        // RS256 to HS256 confusion: the token is labelled HMAC and signed with the public key as the secret. A
        // parser that takes the algorithm from the header and uses the located key would accept it.
        final String input = signingInput("HS256");
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(this.keyPair.getPublic().getEncoded(), "HmacSHA256"));
        final String signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));

        Assertions.assertNull(this.manager.parse(input + "." + signature),
            "A token re-signed as HMAC with the public key must not parse");
    }

    @Test
    public void parseRejectsBlankToken()
    {
        Assertions.assertNull(this.manager.parse(""));
        Assertions.assertNull(this.manager.parse("   "));
    }

    @Test
    public void parseRejectsTokenWithoutExpiry()
    {
        final String unexpiring = Jwts.builder()
            .issuer(SELF_ID)
            .audience().add(SELF_ID).and()
            .subject("guest-patient")
            .header().keyId(selfFingerprint()).and()
            .signWith(this.keyPair.getPrivate())
            .compact();
        Assertions.assertNull(this.manager.parse(unexpiring), "A token without an expiry must not parse");
    }

    @Test
    public void mintedTokensCarryTheConfiguredIdentityUnaltered()
    {
        final String token = this.manager.create("guest-patient", oneHourFromNow(), Map.of()).getToken();
        final Claims claims = Jwts.parser().verifyWith(this.keyPair.getPublic()).build()
            .parseSignedClaims(token).getPayload();

        Assertions.assertEquals(SELF_ID, claims.getIssuer());
        Assertions.assertEquals(Set.of(SELF_ID), claims.getAudience());
    }

    @Test
    public void anUnusableIdentityRefusesToActivate()
    {
        for (final String identity : new String[] {"", " https://iap.example.org", "a/b:c", "https://exa mple.org"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IapJwtTokenManagerImpl(this.resolverFactory, configWithIdentity(identity)),
                "Activated with the identity '" + identity + "'");
        }
    }

    /**
     * The header and payload of a token that would pass every claim check, for the caller to sign or not.
     *
     * @param algorithm the value of the {@code alg} header
     * @return the two BASE64URL segments, joined by a period
     */
    private String signingInput(final String algorithm)
    {
        final long expiry = System.currentTimeMillis() / 1000 + 3600;
        return base64Url("{\"alg\":\"" + algorithm + "\",\"kid\":\"" + selfFingerprint() + "\"}")
            + "."
            + base64Url("{\"iss\":\"" + SELF_ID + "\",\"aud\":[\"" + SELF_ID
                + "\"],\"sub\":\"attacker\",\"exp\":" + expiry + "}");
    }

    /**
     * Parses a token that must be refused, and returns what was logged meanwhile.
     *
     * @param token the token to parse
     * @return what the test binding of SLF4J wrote to the standard error stream
     */
    private String loggedWhileParsing(final String token)
    {
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Assertions.assertNull(this.manager.parse(token));
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String selfFingerprint()
    {
        return IapJwtTokenManagerImpl.getFingerprint(this.keyPair.getPublic());
    }

    /**
     * A configuration naming the given identity, as DS would supply it.
     *
     * @param identity the configured identity
     * @return the configuration
     */
    static IapJwtTokenManagerConfiguration configWithIdentity(final String identity)
    {
        final IapJwtTokenManagerConfiguration config = mock(IapJwtTokenManagerConfiguration.class);
        when(config.identity()).thenReturn(identity);
        return config;
    }

    private static String base64Url(final String value)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Calendar oneHourFromNow()
    {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        return c;
    }
}
