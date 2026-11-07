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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the extraction prompts from files in the bundle.
 *
 * <p>They are files, not Java strings, so people can write and edit them as plain text. Each one is read once
 * and cached, since they do not change while the bundle runs.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Prompts
{
    /** The system prompt for the intake call, which reads every field out of a document at once. */
    static final String INTAKE_SYSTEM = "intake_system.md";

    private static final String PROMPT_DIRECTORY = "/prompts/";

    private static final Map<String, String> PROMPT_CACHE = new ConcurrentHashMap<>();

    private Prompts()
    {
        // Utility
    }

    /**
     * Reads one prompt, from the cache after the first time.
     *
     * @param name a file name in the bundle's {@code /prompts} folder
     * @return the prompt's text
     * @throws UncheckedIOException if the bundle has no such prompt, or it cannot be read
     */
    static String read(final String name)
    {
        return PROMPT_CACHE.computeIfAbsent(name, Prompts::load);
    }

    private static String load(final String name)
    {
        try (InputStream stored = Prompts.class.getResourceAsStream(PROMPT_DIRECTORY + name)) {
            if (stored == null) {
                throw new UncheckedIOException(new IOException("The bundle carries no prompt named " + name));
            }
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stored.readAllBytes())).toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the prompt named " + name, e);
        }
    }
}
