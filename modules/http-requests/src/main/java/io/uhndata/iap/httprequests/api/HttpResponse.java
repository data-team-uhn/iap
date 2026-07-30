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

/**
 * What a remote service answered: the status code it answered with, and the body it sent. Instances are immutable.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class HttpResponse
{
    private final int statusCode;

    private final String body;

    /**
     * Basic constructor.
     *
     * @param statusCode the HTTP status code of the response
     * @param body the body of the response, never {@code null}, empty when the response had none
     */
    public HttpResponse(final int statusCode, final String body)
    {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    /**
     * The HTTP status code the remote service answered with.
     *
     * @return a status code, e.g. {@code 200}
     */
    public int getStatusCode()
    {
        return this.statusCode;
    }

    /**
     * The body the remote service answered with, exactly as it was sent.
     *
     * @return a string, empty when the response had no body
     */
    public String getBody()
    {
        return this.body;
    }

    /**
     * Whether the remote service accepted the request, i.e. answered with a {@code 2xx} status. A request that
     * reaches a service and is refused by it does not throw, so callers that care must check this.
     *
     * @return {@code true} for any {@code 2xx} status code
     */
    public boolean isSuccessful()
    {
        return this.statusCode >= 200 && this.statusCode < 300;
    }

    @Override
    public String toString()
    {
        return this.statusCode + " " + this.body;
    }
}
