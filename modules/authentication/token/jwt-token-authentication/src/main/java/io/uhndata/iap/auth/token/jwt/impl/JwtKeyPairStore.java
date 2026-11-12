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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Encoders;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * This instance's own RSA key pair, kept on the node at {@link IapJwtTokenManagerImpl#KEY_PATH}. The private key is
 * stored PKCS#8-encoded and the public key X.509-encoded, both in BASE64.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JwtKeyPairStore
{
    private final ResourceResolver resolver;

    /**
     * Work on the key node through the given resolver.
     *
     * @param resolver the service user's resolver, which may read and write the key node
     */
    JwtKeyPairStore(final ResourceResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * Read the key pair from the key node, or create one and store it there if the node holds none.
     *
     * @return the key pair
     * @throws IllegalStateException if the key node cannot be read
     * @throws RepositoryException if the key node cannot be read from or written to
     * @throws GeneralSecurityException if the stored keys cannot be decoded
     * @throws PersistenceException if a new key pair cannot be saved
     */
    KeyPair readOrCreate() throws RepositoryException, GeneralSecurityException, PersistenceException
    {
        final Node keyNode = this.resolver.resolve(IapJwtTokenManagerImpl.KEY_PATH).adaptTo(Node.class);
        if (keyNode == null) {
            // Repoinit creates the node and grants the service user access to it. Without either, this instance is
            // deployed wrong, and stays so until an administrator fixes it
            ErrorLogger.logProblem("JWT key node could not be read",
                ErrorContext.of(JwtKeyPairStore.class, "loadKeys").about(IapJwtTokenManagerImpl.KEY_PATH));
            throw new IllegalStateException("The JWT key node " + IapJwtTokenManagerImpl.KEY_PATH
                + " could not be read");
        }
        if (keyNode.hasProperty(IapJwtTokenManagerImpl.SIGNING_KEY_PROP)
            && keyNode.hasProperty(IapJwtTokenManagerImpl.VERIFY_PROP)) {
            return read(keyNode);
        }
        return create(keyNode);
    }

    private KeyPair read(final Node keyNode) throws RepositoryException, GeneralSecurityException
    {
        final KeyFactory rsa = KeyFactory.getInstance("RSA");
        return new KeyPair(
            rsa.generatePublic(new X509EncodedKeySpec(readKey(keyNode, IapJwtTokenManagerImpl.VERIFY_PROP))),
            rsa.generatePrivate(new PKCS8EncodedKeySpec(readKey(keyNode, IapJwtTokenManagerImpl.SIGNING_KEY_PROP))));
    }

    private KeyPair create(final Node keyNode) throws RepositoryException, PersistenceException
    {
        final KeyPair newPair = Jwts.SIG.RS256.keyPair().build();
        keyNode.setProperty(IapJwtTokenManagerImpl.SIGNING_KEY_PROP,
            Encoders.BASE64.encode(newPair.getPrivate().getEncoded()));
        keyNode.setProperty(IapJwtTokenManagerImpl.VERIFY_PROP,
            Encoders.BASE64.encode(newPair.getPublic().getEncoded()));
        this.resolver.commit();
        return newPair;
    }

    /**
     * Read a BASE64-encoded key from a property of the key node.
     *
     * @param keyNode the node holding the key
     * @param propName the name of the property holding the key
     * @return the decoded key bytes
     * @throws InvalidKeySpecException if the property does not hold BASE64
     */
    private byte[] readKey(final Node keyNode, final String propName)
        throws RepositoryException, InvalidKeySpecException
    {
        try {
            return Decoders.BASE64.decode(keyNode.getProperty(propName).getString());
        } catch (final DecodingException e) {
            throw new InvalidKeySpecException("The key in " + propName + " is not valid BASE64", e);
        }
    }
}
