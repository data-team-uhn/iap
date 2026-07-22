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
 * Implementation of a {@link Locator} for JWT validation key lookup. A fresh instance is used per token: while
 * locating the signature verification key it also records the expected issuer for that signer, so the caller can
 * validate the {@code iss} claim without a second repository lookup.
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
     * Create a locator that resolves the key used to verify an inbound JWT's signature.
     *
     * @param verificationKey our own public key, used for tokens signed by this instance
     * @param resolver the resource resolver factory used to look up trusted peers' keys
     * @param selfID the fingerprint identifying tokens signed by this instance
     * @param selfIssuer the expected issuer for tokens signed by this instance
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
     * The verification details for a trusted peer, read from the repository within a single session.
     *
     * @param verificationKey the peer's public key, used to verify the JWT signature
     * @param issuer the peer's expected {@code iss} claim value
     * @version $Id$
     * @since 0.1.0
     */
    private record PeerKeyDetails(PublicKey verificationKey, String issuer)
    {
    }

    /**
     * In an instance where the JWT does not belong to us, look up the peer's verification details in
     * {@code /jcr:system/iap:jwt/}. Everything needed is read while the session is open, so the returned value is
     * safe to use after the session closes.
     *
     * @param keyID The ID under which to find the key
     * @return the peer's public key and expected issuer
     * @throws JwtException if the peer is unknown, its key is missing/unreadable, or the key ID is unsafe
     */
    private PeerKeyDetails lookupPeerDetails(final String keyID)
    {
        final Pattern pattern = Pattern.compile("\\P{Alnum}");
        // Ensure the keyID is sanitized, disallow usage and return nothing if not
        if (pattern.matcher(keyID).find()) {
            throw new JwtException(String.format("Unsafe peer key: %s", keyID));
        }

        final String resourcePath = "/jcr:system/iap:jwt/" + keyID;
        try (ResourceResolver resolver = this.rrf.getServiceResourceResolver(null)) {
            // Grab the appropriate key node, if it exists
            final Resource res = resolver.resolve(resourcePath);
            final Node keyNode = res == null ? null : res.adaptTo(Node.class);
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
            // Should not happen, this is to catch java.security.NoSuchAlgorithmException
            // or java.security.spec.InvalidKeySpecException, but KeyFactory should be able to load RSA
            throw new JwtException(String.format("Failed to load decryption algorithm: %s", e.getMessage()));
        } catch (RepositoryException e) {
            throw new JwtException(String.format("Failed to load JWT Verification key: %s", e.getMessage()));
        }
    }

    /**
     * Extracts the `kid` header from a given JWT Header.
     *
     * @param header The JWT Header
     * @return The `kid` header, or {@code null} if the given header does not correspond to a JwsHeader/JweHeader
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
     * The expected issuer for the signer resolved by the most recent {@link #locate(Header)} call.
     *
     * @return the expected {@code iss} claim value, or {@code null} if no signed token has been located
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
            // No key ID means we cannot identify the signer, so there is no key to verify against.
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
