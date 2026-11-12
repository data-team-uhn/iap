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

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.jackrabbit.api.security.authentication.token.TokenCredentials;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenInfo;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtException;
import io.uhndata.iap.auth.token.IapToken;

/**
 * A signed JWT, held as an Oak {@link TokenInfo}. The token is either just minted or read back from its string form.
 *
 * <p>A minted token keeps every extra claim it was signed with as a public attribute. A token read back keeps only
 * the {@code iap:sessionSubject} claim.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class IapJwtTokenImpl implements IapToken
{
    /** The signed token string, in JWS compact form. */
    private final String loginToken;

    /** The username the token authenticates: its {@code sub} claim. */
    private final String userId;

    /** The expiration time of the token: its {@code exp} claim. */
    private final Calendar expirationTime;

    /** The extra claims the token carries, such as {@code iap:sessionSubject}. */
    private final Map<String, String> attributes;

    /**
     * Read a token from a JWT whose signature and claims were already checked.
     *
     * @param jwt the parsed JWT, with an {@code exp} claim
     * @param token the string the JWT was parsed from
     * @throws JwtException if the JWT's payload is not a set of claims
     */
    public IapJwtTokenImpl(final Jwt<?, ?> jwt, final String token)
    {
        Object payload = jwt.getPayload();
        if (payload instanceof final Claims claims) {
            this.loginToken = token;
            this.userId = claims.getSubject();
            this.expirationTime = new Calendar.Builder().setInstant(claims.getExpiration()).build();
            Map<String, String> parsedAttributes = new HashMap<>();
            if (claims.containsKey("iap:sessionSubject")) {
                parsedAttributes.put("iap:sessionSubject", String.valueOf(claims.get("iap:sessionSubject")));
            }
            this.attributes = parsedAttributes;
        } else {
            throw new JwtException("Unhandled payload type");
        }
    }

    /**
     * Hold a token that was just minted.
     *
     * @param jws the signed token string, in JWS compact form
     * @param userId the username the token authenticates
     * @param expiration the time after which the token is no longer valid
     * @param attributes the extra claims signed into the token, as names and values; empty for none
     */
    public IapJwtTokenImpl(final String jws, final String userId, final Calendar expiration,
        final Map<String, String> attributes)
    {
        this.loginToken = jws;
        this.userId = userId;
        this.expirationTime = (Calendar) expiration.clone();
        this.attributes = new HashMap<String, String>(attributes);
    }

    // ------------------------------------------------------< TokenInfo >---

    @Override
    public String getUserId()
    {
        return this.userId;
    }

    @Override
    public String getToken()
    {
        return this.loginToken;
    }

    @Override
    public boolean isExpired(final long loginTime)
    {
        return this.expirationTime.toInstant().getEpochSecond() * 1000 < loginTime;
    }

    @Override
    public boolean resetExpiration(final long loginTime)
    {
        // The expiration time is signed into the token
        return false;
    }

    @Override
    public boolean remove()
    {
        // A signed JWT cannot be revoked
        return false;
    }

    @Override
    public boolean matches(final TokenCredentials tokenCredentials)
    {
        return this.loginToken.equals(tokenCredentials.getToken());
    }

    @Override
    public Map<String, String> getPrivateAttributes()
    {
        // Anyone holding a JWT can read its claims
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getPublicAttributes()
    {
        return new HashMap<String, String>(this.attributes);
    }

    @Override
    public Calendar getExpirationTime()
    {
        return (Calendar) this.expirationTime.clone();
    }
}
