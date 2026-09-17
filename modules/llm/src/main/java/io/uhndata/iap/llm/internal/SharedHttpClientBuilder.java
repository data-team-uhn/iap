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
package io.uhndata.iap.llm.internal;

import java.time.Duration;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

/**
 * Hands LangChain4j an HTTP client that already exists, so every model built for one provider shares one
 * connection pool instead of opening its own.
 *
 * <p>LangChain4j reads the timeouts back and sets them again before it calls {@link #build()}. The client is
 * already built with the timeouts it was given, so the setters change nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SharedHttpClientBuilder implements HttpClientBuilder
{
    private final HttpClient client;

    private final Duration connectTimeout;

    private final Duration readTimeout;

    SharedHttpClientBuilder(final HttpClient client, final Duration connectTimeout, final Duration readTimeout)
    {
        this.client = client;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public Duration connectTimeout()
    {
        return this.connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(final Duration timeout)
    {
        return this;
    }

    @Override
    public Duration readTimeout()
    {
        return this.readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(final Duration timeout)
    {
        return this;
    }

    @Override
    public HttpClient build()
    {
        return this.client;
    }
}
