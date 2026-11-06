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

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Issues and verifies the signed JWTs that IAP instances authenticate to each other with.
 *
 * <p>This instance signs with RS256 and its own RSA key pair, kept on the node at {@link #KEY_PATH}. The first
 * activation creates the pair. A token names its signer in its {@code kid} header, as the fingerprint of the
 * signer's public key.</p>
 *
 * <p>{@link TokenManager#parse(String) parse} accepts a token signed by this instance or by a registered peer. The
 * token must not have expired, must name this instance's identity in {@code aud}, and must carry the {@code iss}
 * registered for its signer. A token with no {@code exp} claim is refused.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, property = "service.ranking:Integer=50", service = { TokenManager.class })
@Designate(ocd = IapJwtTokenManagerConfiguration.class)
public final class IapJwtTokenManagerImpl implements TokenManager
{
    /** The property holding this instance's private key, PKCS#8-encoded in BASE64. */
    public static final String SIGNING_KEY_PROP = "key";

    /** The property holding a public key, X.509-encoded in BASE64: this instance's own, or a peer's. */
    public static final String VERIFY_PROP = "verify";

    /** The property of a peer's node holding the {@code iss} its tokens must carry. */
    public static final String ISSUER_PROP = "iss";

    /** The JCR path under which every key node lives: this instance's own, and one per peer named by fingerprint. */
    public static final String KEY_ROOT = "/jcr:system/iap-jwt";

    /** The JCR path of the node holding this instance's own key pair. */
    public static final String KEY_PATH = KEY_ROOT + "/JWTRSA256Key";

    private static final Logger LOGGER = LoggerFactory.getLogger(IapJwtTokenManagerImpl.class);

    private final PrivateKey signingKey;

    private final PublicKey verificationKey;

    /** This instance's identity: the {@code iss} of every token it mints, and the {@code aud} it accepts. */
    private final String identity;

    /** The fingerprint of this instance's public key, which its tokens carry as {@code kid}. */
    private final String selfID;

    private final ResourceResolverFactory rrf;

    /**
     * Load this instance's key pair, or create and store one if the key node holds none. If the key node cannot be
     * read, the component does not activate. If the service user cannot log in, or the keys cannot be decoded or
     * stored, it activates but neither mints nor accepts tokens.
     *
     * @param rrf supplies the service user that reads and writes the key nodes
     * @param config this instance's identity
     * @throws IllegalArgumentException if the configured identity is not usable; the component then does not
     *     activate
     */
    @Activate
    public IapJwtTokenManagerImpl(@Reference final ResourceResolverFactory rrf,
        final IapJwtTokenManagerConfiguration config)
    {
        if (!JwtIssuers.isUsable(config.identity())) {
            throw new IllegalArgumentException("Unusable JWT identity configured: " + config.identity());
        }
        this.rrf = rrf;
        this.identity = config.identity();
        PrivateKey signing = null;
        PublicKey verification = null;
        try (ResourceResolver resolver = rrf.getServiceResourceResolver(null)) {
            Resource res = resolver.resolve(KEY_PATH);
            Node keyNode = res.adaptTo(Node.class);
            if (keyNode == null) {
                LOGGER.error("Failed to load JWT Signing key: node {} could not be read",
                    KEY_PATH);
                throw new ExceptionInInitializerError(KEY_PATH);
            }

            if (keyNode.hasProperty(SIGNING_KEY_PROP)
                && keyNode.hasProperty(VERIFY_PROP)) {
                // Private key is PKCS-encoded
                signing = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(readKey(keyNode, SIGNING_KEY_PROP)));
                // Public key is X.509-encoded
                verification = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(readKey(keyNode, VERIFY_PROP)));
            } else {
                KeyPair newPair = Jwts.SIG.RS256.keyPair().build();
                keyNode.setProperty(SIGNING_KEY_PROP,
                    Encoders.BASE64.encode(newPair.getPrivate().getEncoded()));
                keyNode.setProperty(VERIFY_PROP,
                    Encoders.BASE64.encode(newPair.getPublic().getEncoded()));
                resolver.commit();

                signing = newPair.getPrivate();
                verification = newPair.getPublic();
            }
        } catch (LoginException e) {
            LOGGER.error("Service access not granted: {}", e.getMessage());
            ErrorLogger.logError(e, ErrorContext.of(IapJwtTokenManagerImpl.class, "serviceLogin"));
        } catch (Exception e) {
            LOGGER.error("Failed to load JWT Signing key from node: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(IapJwtTokenManagerImpl.class, "loadKeys"));
        }
        this.signingKey = signing;
        this.verificationKey = verification;
        this.selfID = getFingerprint(verification);
    }

    /**
     * Read a BASE64-encoded key from a property of a key node.
     *
     * @param keyNode the node holding the key
     * @param propName the name of the property holding the key, {@link #SIGNING_KEY_PROP} or {@link #VERIFY_PROP}
     * @return the decoded key bytes
     */
    private byte[] readKey(final Node keyNode, final String propName) throws RepositoryException
    {
        return Decoders.BASE64.decode(
            keyNode.getProperty(propName).getString()
        );
    }

    /**
     * The fingerprint of a public key, which names a peer's key node and fills the {@code kid} header.
     *
     * @param publicKey the key, or {@code null}
     * @return the hexadecimal SHA-256 of the key's X.509 encoding in BASE64, or {@code null} if the key is
     *     {@code null}
     */
    public static String getFingerprint(final PublicKey publicKey)
    {
        if (publicKey == null)
        {
            return null;
        }

        return DigestUtils.sha256Hex(Encoders.BASE64.encode(publicKey.getEncoded()));
    }

    @Override
    public IapJwtTokenImpl create(final String userId, final Calendar expiration,
        final Map<String, String> extraData)
    {
        // Addressed to this instance itself
        return create(userId, expiration, extraData, Set.of(this.identity));
    }

    /**
     * Mint a token addressed to the given instances.
     *
     * @param userId the username the token authenticates, its {@code sub} claim
     * @param expiration the time after which the token is no longer valid, its {@code exp} claim
     * @param extraData extra claims to sign into the token, as names and values; empty for none
     * @param audiences the identities of the instances that should accept the token, its {@code aud} claim
     * @return the new token, or {@code null} if no key pair was loaded at activation
     */
    public IapJwtTokenImpl create(final String userId, final Calendar expiration,
        final Map<String, String> extraData, final Set<String> audiences)
    {
        if (this.signingKey == null || this.verificationKey == null) {
            return null;
        }
        String jws = Jwts.builder()
            .claims(extraData)
            .issuer(this.identity)
            .audience().add(audiences).and()
            .subject(userId)
            .expiration(expiration.getTime())
            .header().keyId(this.selfID).and()
            .signWith(this.signingKey)
            .compact();
        return new IapJwtTokenImpl(jws, userId, expiration, extraData);
    }

    /**
     * Check the claims of a token whose signature has been verified. The token must have an {@code exp} claim, name
     * this instance in {@code aud}, and carry the {@code iss} registered for the key that signed it.
     *
     * @param jwt the parsed JWT
     * @param locator the locator that found the key the signature was verified with
     * @return always {@code true}; a check that fails throws instead
     * @throws JwtException if the payload is not a set of claims, or a check fails
     */
    private boolean areClaimsValid(final Jwt<?, ?> jwt, final IapJwtVerificationLocatorImpl locator)
        throws JwtException
    {
        Object payload = jwt.getPayload();
        if (payload instanceof final Claims claims) {
            String expectedIssuer = locator.getResolvedIssuer();

            if (claims.getExpiration() == null) {
                // A token with no expiry is valid forever, and a JWT cannot be revoked
                throw new JwtException("The given JWT is missing an `exp` claim.");
            } else if (claims.getAudience() == null) {
                throw new JwtException("The given JWT is missing an `aud` claim.");
            } else if (!claims.getAudience().contains(this.identity)) {
                throw new JwtException("Our server (" + this.identity
                    + ") is not in the list of JWT audiences for the given JWT.");
            } else if (expectedIssuer == null || !expectedIssuer.equals(claims.getIssuer())) {
                throw new JwtException("The given JWT's issuer does not match the expected issuer.");
            }
            return true;
        } else {
            throw new JwtException("The given JWT's payload is in an unknown format.");
        }
    }

    @Override
    public IapJwtTokenImpl parse(final String loginToken)
    {
        if (loginToken == null || loginToken.isBlank() || this.verificationKey == null) {
            return null;
        }
        try {
            final IapJwtVerificationLocatorImpl locator =
                new IapJwtVerificationLocatorImpl(this.verificationKey, this.rrf, this.selfID, this.identity);
            Jwt<?, ?> jwt = Jwts.parser()
                .keyLocator(locator)
                .build()
                .parseSignedClaims(loginToken);

            if (areClaimsValid(jwt, locator)) {
                return new IapJwtTokenImpl(jwt, loginToken);
            }
        } catch (JwtException e) {
            // Not recorded through error tracking: the message can quote the token's header and claims
            LOGGER.error("JWT validation failed: {}", e.getMessage());
        }
        return null;
    }
}
