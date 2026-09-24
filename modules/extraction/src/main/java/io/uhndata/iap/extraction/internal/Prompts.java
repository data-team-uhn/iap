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
 * The prompts and response schemas the extraction stages send, read from the bundle.
 *
 * <p>They are files rather than string constants because they are written and revised as prose by people
 * reasoning about what the model should be told, and reading them as text beats reading them as escaped Java.
 * Each is read once and kept, since they never change while the bundle is running.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Prompts
{
    /** Domain knowledge for reading a research proposal: what one is, judged against ICH-GCP. */
    static final String IS_PROPOSAL_SYSTEM = "is_proposal_system.md";

    /** The ICH-GCP protocol content reference, rubrics B.1 to B.17. */
    static final String PROTOCOL_STRUCTURE = "protocol_structure.md";

    /** What the intake pass is told about reading every field out of a document at once. */
    static final String INTAKE_SYSTEM = "intake_system.md";

    private static final String DIRECTORY = "/prompts/";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Prompts()
    {
        // Utility
    }

    /**
     * Read one prompt or schema.
     *
     * @param name the file name, one of the constants on this class
     * @return its content
     * @throws UncheckedIOException if the bundle does not carry it, which is a packaging fault rather than
     *             anything a caller can answer for
     */
    static String read(final String name)
    {
        return CACHE.computeIfAbsent(name, Prompts::load);
    }

    private static String load(final String name)
    {
        try (InputStream stored = Prompts.class.getResourceAsStream(DIRECTORY + name)) {
            if (stored == null) {
                throw new UncheckedIOException(new IOException("The bundle carries no prompt named " + name));
            }
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stored.readAllBytes())).toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the prompt named " + name, e);
        }
    }
}
