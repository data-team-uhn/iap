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
package io.uhndata.iap.auth.token;

import java.util.Calendar;

import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Custom extension of {@link TokenInfo} with support for retrieving the token expiration time, who issued it, and its
 * other claims.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface IapToken extends TokenInfo
{
    /**
     * Obtain the expiration time from this token.
     *
     * @return the expiration date, or {@code null} if there's no expiration date set
     */
    @Nullable
    Calendar getExpirationTime();

    /**
     * Obtain the identity of the instance that issued this token.
     *
     * @return the issuer, or {@code null} if the token does not name one
     */
    @Nullable
    String getIssuer();

    /**
     * Obtain one of this token's claims, such as an item of the extra data it was created with. Unlike
     * {@link #getPublicAttributes()}, which become session attributes, this offers everything the issuer put in the
     * token.
     *
     * @param name the claim's name
     * @return its value, or {@code null} if the token carries no such claim, or one that is not a string
     */
    @Nullable
    String getClaim(@NotNull String name);
}
