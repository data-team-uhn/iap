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

import org.junit.jupiter.api.Test;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link SharedHttpClientBuilder}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SharedHttpClientBuilderTest
{
    private static final Duration CONNECT = Duration.ofSeconds(3);

    private static final Duration READ = Duration.ofSeconds(7);

    private final HttpClient client = new JdkHttpClientBuilder().connectTimeout(CONNECT).readTimeout(READ).build();

    private final SharedHttpClientBuilder shared = new SharedHttpClientBuilder(this.client, CONNECT, READ);

    @Test
    void handsOutTheClientItWasGiven()
    {
        assertSame(this.client, this.shared.build());
        assertSame(this.client, this.shared.build(), "the same one every time");
    }

    // LangChain4j sets the timeouts again before building; the client is already built, so they cannot change
    @Test
    void keepsTheTimeoutsTheClientWasBuiltWith()
    {
        final HttpClient built = this.shared
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .build();

        assertSame(this.client, built);
        assertEquals(CONNECT, this.shared.connectTimeout());
        assertEquals(READ, this.shared.readTimeout());
    }
}
