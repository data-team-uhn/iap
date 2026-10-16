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
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.regex.Pattern;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Encoders;

/**
 * A peer's public key, as submitted for registration and as it will be stored.
 *
 * @param encoded the key in the canonical BASE64 form stored in the {@code verify} property
 * @param fingerprint the key's fingerprint, which is both the node name and the {@code kid} tokens carry
 * @version $Id$
 * @since 0.1.0
 */
record JwtPeerKey(String encoded, String fingerprint)
{
    /** PEM armour and every kind of whitespace, all of which a pasted key file carries and BASE64 does not. */
    private static final Pattern ARMOUR = Pattern.compile("-----[A-Z ]+-----|\\s");

    /** JJWT refuses shorter RSA keys for RS256. */
    private static final int MIN_KEY_BITS = 2048;

    /**
     * Read a submitted public key.
     *
     * @param submitted the key as the caller sent it, BASE64 with or without PEM armour
     * @return the key in the form it will be stored, with its fingerprint
     * @throws GeneralSecurityException if the text is not BASE64, or not an X.509-encoded RSA public key
     */
    static JwtPeerKey parse(final String submitted) throws GeneralSecurityException
    {
        final byte[] der;
        try {
            der = Decoders.BASE64.decode(ARMOUR.matcher(submitted).replaceAll(""));
        } catch (final DecodingException e) {
            throw new InvalidKeySpecException("The key is not valid BASE64", e);
        }
        final RSAPublicKey key =
            (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        final int bits = key.getModulus().bitLength();
        if (bits < MIN_KEY_BITS) {
            throw new InvalidKeySpecException(
                "RSA keys must be at least " + MIN_KEY_BITS + " bits to verify tokens; this one has " + bits);
        }
        return new JwtPeerKey(Encoders.BASE64.encode(key.getEncoded()),
            IapJwtTokenManagerImpl.getFingerprint(key));
    }
}
