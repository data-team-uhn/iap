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

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import io.jsonwebtoken.Header;
import io.jsonwebtoken.JweHeader;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.io.Decoders;

/**
 * Finds the public key to verify a token's signature with, from the token's {@code kid} header: this instance's own
 * key, or a registered peer's. It also remembers the issuer registered for that key, for the {@code iss} check.
 * Use one instance per token.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class IapJwtVerificationLocatorImpl implements Locator<Key>
{
    private final PublicKey verificationKey;

    private final ResourceResolverFactory rrf;

    private final String selfID;

    private final String selfIssuer;

    /** The expected issuer for the signer resolved by the last {@link #locate(Header)} call. */
    private String resolvedIssuer;

    /**
     * Create a locator for one token.
     *
     * @param verificationKey this instance's own public key
     * @param resolver supplies the service user that reads the peers' key nodes
     * @param selfID the fingerprint of this instance's public key, which its tokens carry as {@code kid}
     * @param selfIssuer this instance's identity, the {@code iss} of the tokens it signs
     */
    public IapJwtVerificationLocatorImpl(final PublicKey verificationKey, final ResourceResolverFactory resolver,
        final String selfID, final String selfIssuer)
    {
        this.verificationKey = verificationKey;
        this.rrf = resolver;
        this.selfID = selfID;
        this.selfIssuer = selfIssuer;
    }

    /**
     * What is registered for a peer.
     *
     * @param verificationKey the peer's public key
     * @param issuer the {@code iss} claim the peer's tokens must carry
     * @version $Id$
     * @since 0.1.0
     */
    private record PeerKeyDetails(PublicKey verificationKey, String issuer)
    {
    }

    /**
     * Read a peer's public key and issuer from its node under {@code /jcr:system/iap-jwt/}.
     *
     * @param keyID the token's {@code kid} header, the fingerprint of the peer's public key
     * @return the peer's public key and issuer
     * @throws JwtException if the key ID holds anything but ASCII letters and digits, or no readable key and issuer
     *     are registered under it
     */
    private PeerKeyDetails lookupPeerDetails(final String keyID)
    {
        final Pattern pattern = Pattern.compile("\\P{Alnum}");
        // The key ID is appended to a path, so it must not be able to address another node
        if (pattern.matcher(keyID).find()) {
            throw new JwtException(String.format("Unsafe peer key: %s", keyID));
        }

        final String resourcePath = IapJwtTokenManagerImpl.KEY_ROOT + "/" + keyID;
        try (ResourceResolver resolver = this.rrf.getServiceResourceResolver(null)) {
            final Resource res = resolver.resolve(resourcePath);
            final Node keyNode = res.adaptTo(Node.class);
            if (keyNode == null) {
                throw new JwtException(
                    String.format("Failed to load JWT Verification key for peer %s: node %s could not be read",
                        keyID, resourcePath)
                );
            }
            if (!keyNode.hasProperty(IapJwtTokenManagerImpl.VERIFY_PROP)) {
                throw new JwtException(
                    String.format("Failed to load JWT Verification key: node %s missing %s property",
                        keyNode.getIdentifier(), IapJwtTokenManagerImpl.VERIFY_PROP)
                );
            }

            final byte[] pubBytes = Decoders.BASE64.decode(
                keyNode.getProperty(IapJwtTokenManagerImpl.VERIFY_PROP).getString()
            );
            final PublicKey peerKey =
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubBytes));
            final String issuer = keyNode.getProperty(IapJwtTokenManagerImpl.ISSUER_PROP).getString();
            return new PeerKeyDetails(peerKey, issuer);
        } catch (LoginException e) {
            throw new JwtException(String.format("Service access not granted: %s", e.getMessage()));
        } catch (GeneralSecurityException e) {
            // The stored key is not an X.509-encoded RSA public key
            throw new JwtException(String.format("Failed to read the peer's verification key: %s", e.getMessage()));
        } catch (RepositoryException e) {
            throw new JwtException(String.format("Failed to load JWT Verification key: %s", e.getMessage()));
        }
    }

    /**
     * The {@code kid} of a token's header.
     *
     * @param header the token's header
     * @return the key ID, or {@code null} if the header has none, or belongs to neither a JWS nor a JWE
     */
    private static String getKey(final Header header)
    {
        if (header instanceof JwsHeader) {
            return ((JwsHeader) header).getKeyId();
        } else if (header instanceof JweHeader) {
            return ((JweHeader) header).getKeyId();
        }
        return null;
    }

    /**
     * The issuer registered for the key the most recent {@link #locate(Header)} call found.
     *
     * @return the {@code iss} the token must carry, or {@code null} if no key has been found
     */
    public String getResolvedIssuer()
    {
        return this.resolvedIssuer;
    }

    @Override
    public Key locate(final Header header)
    {
        final String keyID = getKey(header);

        if (keyID == null) {
            // Without a key ID the signer is unknown, and the token cannot be verified
            return null;
        }

        if (keyID.equals(this.selfID)) {
            this.resolvedIssuer = this.selfIssuer;
            return this.verificationKey;
        }

        final PeerKeyDetails peer = lookupPeerDetails(keyID);
        this.resolvedIssuer = peer.issuer();
        return peer.verificationKey();
    }
}
