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
 * Sends HTTP requests to services outside IAP.
 *
 * <p>
 * A request that reaches the service returns its answer, whatever the status. A service answering {@code 400} comes
 * back as an {@link HttpResponse} that is not {@link HttpResponse#isSuccessful successful}, and nothing throws. Only a
 * request that could not be made, or got no answer in time, throws.
 * </p>
 *
 * <p>
 * An address can be a secret: a chat webhook carries its token in its path. The messages this module writes never
 * quote an address. At most they name the scheme, host and port of the service.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface HttpRequests
{
    /**
     * Sends a POST request with a body encoded in UTF-8.
     *
     * @param url the absolute {@code http} or {@code https} URL to post to
     * @param body the body to send, as text
     * @param contentType the media type of the body without parameters, such as {@code application/json}; the
     *     request's {@code Content-Type} adds {@code ; charset=UTF-8} to it
     * @return the status code and the body the service answered with, whatever the status
     * @throws IOException if the URL or the content type is not valid, the service could not be reached or did not
     *     start answering in time, or the thread was interrupted
     */
    @NotNull
    HttpResponse post(@NotNull String url, @NotNull String body, @NotNull String contentType) throws IOException;

    /**
     * Sends a POST request with a body encoded in the given charset.
     *
     * @param url the absolute {@code http} or {@code https} URL to post to
     * @param body the body to send, as text
     * @param contentType the media type of the body without parameters, such as {@code application/json}; the
     *     request's {@code Content-Type} adds the charset to it, as in {@code application/json; charset=UTF-8}
     * @param charset the charset to encode the body with
     * @return the status code and the body the service answered with, whatever the status
     * @throws IOException if the URL or the content type is not valid, the service could not be reached or did not
     *     start answering in time, or the thread was interrupted
     */
    @NotNull
    HttpResponse post(@NotNull String url, @NotNull String body, @NotNull String contentType,
        @NotNull Charset charset) throws IOException;
}
