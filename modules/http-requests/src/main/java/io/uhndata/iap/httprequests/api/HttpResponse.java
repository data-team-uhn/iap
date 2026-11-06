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
package io.uhndata.iap.httprequests.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a service answered: the status code and the body. Instances are immutable.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class HttpResponse
{
    private final int statusCode;

    private final String body;

    /**
     * A response with the given status code and body.
     *
     * @param statusCode the HTTP status code, such as {@code 200}
     * @param body the body as text; {@code null} or empty if the response had none
     */
    public HttpResponse(final int statusCode, @Nullable final String body)
    {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    /**
     * The HTTP status code the service answered with.
     *
     * @return the status code, such as {@code 200} or {@code 404}
     */
    public int getStatusCode()
    {
        return this.statusCode;
    }

    /**
     * The body the service answered with, as text.
     *
     * @return the body; empty if the response had none
     */
    @NotNull
    public String getBody()
    {
        return this.body;
    }

    /**
     * Whether the service accepted the request.
     *
     * @return {@code true} for a status code from {@code 200} to {@code 299}
     */
    public boolean isSuccessful()
    {
        return this.statusCode >= 200 && this.statusCode < 300;
    }

    @Override
    @NotNull
    public String toString()
    {
        return this.statusCode + " " + this.body;
    }
}
