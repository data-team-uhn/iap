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
import org.jetbrains.annotations.Nullable;

/**
 * A login token, as Oak's {@link TokenInfo} describes one, that also tells when it expires.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface IapToken extends TokenInfo
{
    /**
     * The time after which this token is no longer valid.
     *
     * @return the expiration time, or {@code null} if the token has none
     */
    @Nullable
    Calendar getExpirationTime();
}
