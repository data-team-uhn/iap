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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What every model call read, one line per call, kept beside the document it read.
 *
 * <p>Step 2 uses this to decide what is left to read. Coverage is counted per field, not overall. If field A
 * reads Chunk-1 and field B has not, Chunk-1 is still unread for B. Counting it as read for both would mean
 * B is reported absent from a chunk nobody ever searched for its answer.
 *
 * <p>Append-only. A line records a call that happened, so nothing rewrites one.
 *
 * {@snippet lang=json :
 * {"call": 1, "step": "gate", "fields": ["is_protocol"], "chunks": [], "durationMs": 4210, "outcome": "ok"}
 * {"call": 2, "step": "intake", "fields": ["title"], "chunks": ["Chunk-1"], "durationMs": 11840, "outcome": "ok"}
 * {"call": 3, "step": "extract", "fields": ["sample"], "chunks": ["Chunk-9"], "durationMs": 9020, "outcome": "failed"}
 * {"call": 4, "step": "sweep", "fields": ["sample"], "chunks": ["Chunk-6"], "durationMs": 7300, "outcome": "degraded"}
 * }
 *
 * <p>{@code chunks} lists what the call read in full. The gate's is empty because it only sees the document's
 * opening and its table of contents.
 *
 * <p>{@code outcome} is {@code ok} when the answer was read, {@code degraded} when the model answered but the
 * answer could not be read, and {@code failed} when the call never came back. A failed call read nothing, so
 * it does not count as coverage. {@code durationMs} is how long the call took, the wait for a slot included.
 * It is what shows a slow provider, and what the parallel-call cap is measured against.
 *
 * <p>It lives in the repository, not beside the parse outputs. The staging directory is wiped as soon as a
 * parse is read in, so a file there would be gone before the next call.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LlmCallTracker
{
    /** The child of a {@code sub:File} holding the record. */
    static final String CHILD = "llmCallTracker";

    /** The step names a line can carry. */
    static final String GATE = "gate";

    static final String INTAKE = "intake";

    static final String EXTRACT = "extract";

    static final String SWEEP = "sweep";

    /** The outcomes a line can carry. */
    static final String OK = "ok";

    static final String DEGRADED = "degraded";

    static final String FAILED = "failed";

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmCallTracker.class);

    private static final String MIME_TYPE = "application/jsonl";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String NT_FILE = "nt:file";

    private static final String NT_RESOURCE = "nt:resource";

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    private static final String JCR_MIME_TYPE = "jcr:mimeType";

    private static final String FIELDS = "fields";

    private static final String CHUNKS = "chunks";

    private static final String DURATION_MS = "durationMs";

    private static final String OUTCOME = "outcome";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private LlmCallTracker()
    {
        // Utility
    }

    /**
     * One call, as the record holds it.
     *
     * @param call which call this was, counting from 1
     * @param step what kind of call it was
     * @param fields what it asked about
     * @param chunks what it read in full
     * @param durationMs how long it took, in milliseconds
     * @param outcome how it ended: {@link #OK}, {@link #DEGRADED} or {@link #FAILED}
     * @version $Id$
     * @since 0.1.0
     */
    record Call(int call, String step, List<String> fields, List<String> chunks, long durationMs, String outcome)
    {
        /**
         * Takes copies, so a recorded call cannot be edited afterwards.
         *
         * @param call which call this was
         * @param step what kind of call it was
         * @param fields what it asked about
         * @param chunks what it read in full
         * @param durationMs how long it took
         * @param outcome how it ended
         */
        Call
        {
            fields = List.copyOf(fields);
            chunks = List.copyOf(chunks);
        }

        /** Whether the model saw these chunks. A failed call never got an answer, so it read nothing. */
        boolean readItsChunks()
        {
            return !FAILED.equals(this.outcome);
        }
    }

    /**
     * Add a line for a call that has just happened.
     *
     * @param file the {@code sub:File} the call read
     * @param step what kind of call it was
     * @param fields what it asked about
     * @param chunks what it read in full
     * @param durationMs how long it took, in milliseconds
     * @param outcome how it ended: {@link #OK}, {@link #DEGRADED} or {@link #FAILED}
     * @throws PersistenceException if the record cannot be written
     */
    static void append(final Resource file, final String step, final List<String> fields,
        final List<String> chunks, final long durationMs, final String outcome) throws PersistenceException
    {
        final List<Call> calls = new ArrayList<>(read(file));
        calls.add(new Call(calls.size() + 1, step, fields, chunks, durationMs, outcome));
        write(file, calls);
    }

    /**
     * Milliseconds since a {@link System#nanoTime()} reading.
     *
     * @param startedNanos when the call started
     * @return how long ago that was, in milliseconds
     */
    static long elapsedMs(final long startedNanos)
    {
        return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
    }

    /**
     * Every call made against this document so far, in the order they were made.
     *
     * @param file the {@code sub:File}
     * @return the calls, empty when none has been recorded
     */
    static List<Call> read(final Resource file)
    {
        final Resource content = child(file);
        if (content == null) {
            return List.of();
        }
        final InputStream bytes = content.getValueMap().get(JCR_DATA, InputStream.class);
        if (bytes == null) {
            return List.of();
        }
        try (InputStream in = bytes) {
            return parse(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(in.readAllBytes())).toString());
        } catch (final IOException e) {
            // An unreadable record makes every chunk look unread. That costs tokens, not answers, so it is
            // better than failing the run.
            LOGGER.warn("Could not read the call record on {}: {}", file.getPath(), e.getMessage());
            return List.of();
        }
    }

    /**
     * The chunks some call has already read while asking about a field.
     *
     * @param file the {@code sub:File}
     * @param field the field
     * @return the chunk ids examined for it, empty when none has been
     */
    static Set<String> examined(final Resource file, final String field)
    {
        final Set<String> seen = new HashSet<>();
        for (final Call call : read(file)) {
            if (call.readItsChunks() && call.fields().contains(field)) {
                seen.addAll(call.chunks());
            }
        }
        return seen;
    }

    /**
     * Every chunk any call has read in full, whatever it was asking about.
     *
     * @param file the {@code sub:File}
     * @return the chunk ids read so far
     */
    static Set<String> everythingRead(final Resource file)
    {
        final Set<String> seen = new HashSet<>();
        for (final Call call : read(file)) {
            if (call.readItsChunks()) {
                seen.addAll(call.chunks());
            }
        }
        return seen;
    }

    private static Resource child(final Resource file)
    {
        final Resource holder = file.getChild(CHILD);
        return holder == null ? null : holder.getChild(JCR_CONTENT);
    }

    private static List<Call> parse(final String jsonl)
    {
        final List<Call> calls = new ArrayList<>();
        for (final String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try (JsonReader reader = Json.createReader(new StringReader(line))) {
                final JsonObject read = reader.readObject();
                // Lines written before timing was recorded carry neither. They were only ever written after
                // an answer came back.
                calls.add(new Call(read.getInt("call", calls.size() + 1), read.getString("step", ""),
                    strings(read.getJsonArray(FIELDS)), strings(read.getJsonArray(CHUNKS)),
                    read.containsKey(DURATION_MS) ? read.getJsonNumber(DURATION_MS).longValue() : 0L,
                    read.getString(OUTCOME, OK)));
            } catch (final RuntimeException e) {
                LOGGER.warn("Skipping a call record line that could not be read: {}", e.getMessage());
            }
        }
        return calls;
    }

    private static List<String> strings(final JsonArray values)
    {
        if (values == null) {
            return List.of();
        }
        final List<String> read = new ArrayList<>(values.size());
        for (final JsonValue value : values) {
            if (value.getValueType() == JsonValue.ValueType.STRING) {
                read.add(((JsonString) value).getString());
            }
        }
        return read;
    }

    private static void write(final Resource file, final List<Call> calls) throws PersistenceException
    {
        final StringBuilder jsonl = new StringBuilder();
        for (final Call call : calls) {
            jsonl.append(describe(call)).append('\n');
        }
        final byte[] bytes = jsonl.toString().getBytes(StandardCharsets.UTF_8);
        final ResourceResolver resolver = file.getResourceResolver();
        final Resource existing = child(file);
        if (existing == null) {
            final Resource holder = resolver.create(file, CHILD, Map.of(PRIMARY_TYPE, NT_FILE));
            resolver.create(holder, JCR_CONTENT, Map.of(
                PRIMARY_TYPE, NT_RESOURCE,
                JCR_MIME_TYPE, MIME_TYPE,
                JCR_DATA, new ByteArrayInputStream(bytes)));
            return;
        }
        final ModifiableValueMap properties = existing.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record a call on " + file.getPath());
        }
        properties.put(JCR_DATA, new ByteArrayInputStream(bytes));
    }

    private static String describe(final Call call)
    {
        final JsonArrayBuilder fields = Json.createArrayBuilder();
        call.fields().forEach(fields::add);
        final JsonArrayBuilder chunks = Json.createArrayBuilder();
        call.chunks().forEach(chunks::add);
        return Json.createObjectBuilder()
            .add("call", call.call())
            .add("step", call.step())
            .add(FIELDS, fields)
            .add(CHUNKS, chunks)
            .add(DURATION_MS, call.durationMs())
            .add(OUTCOME, call.outcome())
            .build().toString();
    }
}
