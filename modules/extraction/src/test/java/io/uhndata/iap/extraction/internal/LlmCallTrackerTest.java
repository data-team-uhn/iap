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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LlmCallTracker}: the record of what every call read, which is what lets a later pass
 * be targeted rather than blind.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LlmCallTrackerTest
{
    private static final String FILE_PATH = "/Submissions/s1/proposal/v1/file";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    private static final String TITLE = "title";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.file = this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", File.RESOURCE_TYPE));
    }

    @Test
    void answersNothingBeforeAnyCallHasBeenMade()
    {
        assertEquals(List.of(), LlmCallTracker.read(this.file));
        assertEquals(Set.of(), LlmCallTracker.examined(this.file, TITLE));
        assertEquals(Set.of(), LlmCallTracker.everythingRead(this.file));
    }

    @Test
    void recordsOneCallAndReadsItBack() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE, "objectives"),
            List.of(CHUNK_1, CHUNK_2), 0, LlmCallTracker.OK);

        final List<LlmCallTracker.Call> calls = LlmCallTracker.read(this.file);

        assertEquals(1, calls.size());
        assertEquals(1, calls.get(0).call());
        assertEquals(LlmCallTracker.INTAKE, calls.get(0).step());
        assertEquals(List.of(TITLE, "objectives"), calls.get(0).fields());
        assertEquals(List.of(CHUNK_1, CHUNK_2), calls.get(0).chunks());
    }

    @Test
    void numbersEachCallInTurn() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.GATE, List.of("is_protocol"), List.of(), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of(TITLE), List.of(CHUNK_2),
            0, LlmCallTracker.OK);

        assertEquals(List.of(1, 2, 3), LlmCallTracker.read(this.file).stream().map(LlmCallTracker.Call::call)
            .toList());
    }

    // The gate answers from an opening and a table of contents, never from a chunk
    @Test
    void recordsThatTheGateReadNoChunks() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.GATE, List.of("is_protocol"), List.of(), 0, LlmCallTracker.OK);

        assertEquals(List.of(), LlmCallTracker.read(this.file).get(0).chunks());
        assertEquals(Set.of(), LlmCallTracker.everythingRead(this.file));
    }

    // Coverage has to be per field. Two fields reading the same chunk is normal, and counting one field's
    // reading as the other's would stop the second field ever seeing it.
    @Test
    void countsCoverageOneFieldAtATime() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of("funding"), List.of(CHUNK_2),
            0, LlmCallTracker.OK);

        assertEquals(Set.of(CHUNK_1), LlmCallTracker.examined(this.file, TITLE));
        assertEquals(Set.of(CHUNK_2), LlmCallTracker.examined(this.file, "funding"));
        assertEquals(Set.of(), LlmCallTracker.examined(this.file, "design"));
    }

    @Test
    void gathersWhatEveryCallReadForOneField() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of(TITLE), List.of(CHUNK_2),
            0, LlmCallTracker.OK);

        assertEquals(Set.of(CHUNK_1, CHUNK_2), LlmCallTracker.examined(this.file, TITLE));
    }

    @Test
    void gathersEverythingAnyCallRead() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.SWEEP, List.of("funding"), List.of(CHUNK_2),
            0, LlmCallTracker.OK);

        assertEquals(Set.of(CHUNK_1, CHUNK_2), LlmCallTracker.everythingRead(this.file));
    }

    @Test
    void writesOneJsonObjectPerLine() throws Exception
    {
        LlmCallTracker.append(this.file, LlmCallTracker.GATE, List.of("is_protocol"), List.of(), 0, LlmCallTracker.OK);
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK);

        final String[] lines = stored().split("\n");

        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"call\":1") && lines[0].contains("\"step\":\"gate\""), lines[0]);
        assertTrue(lines[1].contains("\"chunks\":[\"Chunk-1\"]"), lines[1]);
    }

    // A record that cannot be read costs tokens, never an answer, so a bad line is skipped rather than fatal
    @Test
    void skipsALineItCannotRead() throws Exception
    {
        store("not json at all\n{\"call\":2,\"step\":\"intake\",\"fields\":[\"title\"],\"chunks\":[\"Chunk-1\"]}\n");

        assertEquals(1, LlmCallTracker.read(this.file).size());
        assertEquals(Set.of(CHUNK_1), LlmCallTracker.examined(this.file, TITLE));
    }

    @Test
    void copesWithALineThatNamesNoFieldsOrChunks() throws Exception
    {
        store("{\"call\":1,\"step\":\"gate\"}\n");

        final LlmCallTracker.Call call = LlmCallTracker.read(this.file).get(0);

        assertEquals(List.of(), call.fields());
        assertEquals(List.of(), call.chunks());
    }

    @Test
    void ignoresBlankLines() throws Exception
    {
        store("\n{\"call\":1,\"step\":\"gate\",\"fields\":[],\"chunks\":[]}\n\n");

        assertEquals(1, LlmCallTracker.read(this.file).size());
    }

    // A tree nothing may be written to, standing in for one the caller has no write access to
    private static final class ReadOnly extends ResourceWrapper
    {
        ReadOnly(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public Resource getChild(final String relPath)
        {
            final Resource child = super.getChild(relPath);
            return child == null ? null : new ReadOnly(child);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
        }
    }

    @Test
    void saysSoWhenItMayNotRecordACall() throws Exception
    {
        LlmCallTracker.append(this.file, LlmCallTracker.GATE, List.of("is_protocol"), List.of(), 0, LlmCallTracker.OK);

        assertThrows(PersistenceException.class, () -> LlmCallTracker.append(new ReadOnly(this.file),
            LlmCallTracker.INTAKE, List.of(TITLE), List.of(CHUNK_1), 0, LlmCallTracker.OK));
    }

    // Reading it back is what costs tokens when it fails, never an answer, so it must not throw
    @Test
    void answersNothingWhenTheRecordHoldsNoBytes()
    {
        this.context.create().resource(FILE_PATH + "/" + LlmCallTracker.CHILD,
            Map.of("jcr:primaryType", "nt:file"));

        assertEquals(List.of(), LlmCallTracker.read(this.file));
    }

    @Test
    void answersNothingWhenTheRecordIsEmpty()
    {
        final Resource holder = this.context.create().resource(FILE_PATH + "/" + LlmCallTracker.CHILD,
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(holder.getPath() + "/jcr:content",
            Map.of("jcr:primaryType", "nt:resource"));

        assertEquals(List.of(), LlmCallTracker.read(this.file));
    }

    @Test
    void recordsHowLongACallTookAndHowItEnded() throws Exception
    {
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of(TITLE), List.of(CHUNK_1), 1234,
            LlmCallTracker.DEGRADED);

        final LlmCallTracker.Call call = LlmCallTracker.read(this.file).get(0);

        assertEquals(1234, call.durationMs());
        assertEquals(LlmCallTracker.DEGRADED, call.outcome());
        assertTrue(stored().contains("\"durationMs\":1234"), stored());
        assertTrue(stored().contains("\"outcome\":\"degraded\""), stored());
    }

    // The model never answered a failed call, so nothing was read for the field. Counting it would stop every
    // later pass from ever looking there.
    @Test
    void doesNotCountAFailedCallAsCoverage() throws PersistenceException
    {
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of(TITLE), List.of(CHUNK_1), 5,
            LlmCallTracker.FAILED);
        LlmCallTracker.append(this.file, LlmCallTracker.EXTRACT, List.of(TITLE), List.of(CHUNK_2), 5,
            LlmCallTracker.OK);

        assertEquals(Set.of(CHUNK_2), LlmCallTracker.examined(this.file, TITLE));
        assertEquals(Set.of(CHUNK_2), LlmCallTracker.everythingRead(this.file));
        assertEquals(2, LlmCallTracker.read(this.file).size(), "the call still counts as a call");
    }

    @Test
    void readsALineWrittenBeforeTimingWasRecorded() throws Exception
    {
        store("{\"call\":1,\"step\":\"intake\",\"fields\":[\"title\"],\"chunks\":[\"Chunk-1\"]}\n");

        final LlmCallTracker.Call call = LlmCallTracker.read(this.file).get(0);

        assertEquals(0, call.durationMs());
        assertEquals(LlmCallTracker.OK, call.outcome());
        assertEquals(Set.of(CHUNK_1), LlmCallTracker.examined(this.file, TITLE));
    }

    private String stored() throws Exception
    {
        final Resource content = this.file.getChild(LlmCallTracker.CHILD).getChild("jcr:content");
        try (InputStream in = content.getValueMap().get("jcr:data", InputStream.class)) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(in.readAllBytes())).toString();
        }
    }

    private void store(final String jsonl)
    {
        final Resource holder = this.context.create().resource(FILE_PATH + "/" + LlmCallTracker.CHILD,
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(holder.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8))));
    }
}
