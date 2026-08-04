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

import java.io.IOException;
import java.nio.charset.Charset;

import org.jetbrains.annotations.NotNull;

/**
 * Sends HTTP requests to other services, e.g. a chat webhook or a document checking service.
 *
 * <p>
 * Every request is made with a connection and a response timeout, so a service that accepts a connection and then
 * stops answering cannot hold up the caller forever. Reaching a service and being refused by it is not a failure as
 * far as this API is concerned: only a request that could not be made at all throws, while a service answering
 * {@code 400} comes back as an unsuccessful {@link HttpResponse}, which callers that care must check.
 * </p>
 *
 * <p>
 * An address can itself be a secret, since a chat webhook carries its authorization token in its path, so a failure
 * never quotes the address it was given, only the service it was trying to reach.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface HttpRequests
{
    /**
     * Sends a POST request, with a UTF-8 encoded body.
     *
     * @param url the address to post to
     * @param body the body to send
     * @param contentType the media type of the body, e.g. {@code application/json}
     * @return what the service answered
     * @throws IOException if the request could not be made, e.g. the address is not a valid http(s) URL, the
     *     service is unreachable, or it stopped answering
     */
    @NotNull
    HttpResponse post(@NotNull String url, @NotNull String body, @NotNull String contentType) throws IOException;

    /**
     * Sends a POST request, with a body encoded in the given charset.
     *
     * @param url the address to post to
     * @param body the body to send
     * @param contentType the media type of the body, e.g. {@code application/json}
     * @param charset the charset to encode the body with
     * @return what the service answered
     * @throws IOException if the request could not be made, e.g. the address is not a valid http(s) URL, the
     *     service is unreachable, or it stopped answering
     */
    @NotNull
    HttpResponse post(@NotNull String url, @NotNull String body, @NotNull String contentType,
        @NotNull Charset charset) throws IOException;
}
