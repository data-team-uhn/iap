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
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentReader}: asking every call of a plan, and what happens when one goes wrong.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DocumentReaderTest
{
    private static final String TEXT = "# Protocol\n\nThe study title is Sardines for Prediabetes.\n";

    private final LLMClient client = Mockito.mock(LLMClient.class);

    /** Plans one call per key, each asking that one question. */
    private static ExtractionPrompt.Plan createPlan(final String... keys)
    {
        return TestFields.planWithoutCut(TEXT, Arrays.stream(keys).map(key -> TestFields.createGroup(key, key))
            .toList());
    }

    /** Plans one call asking every key's question. */
    private static ExtractionPrompt.Plan createOneCallPlan(final String... keys)
    {
        return TestFields.planWithoutCut(TEXT, List.of(TestFields.createGroup("study", keys)));
    }

    private static String buildReply(final String key, final String value)
    {
        return "{\"" + key + "\": {\"evidence\": [], \"reasoning\": \"\", \"value\": \"" + value
            + "\", \"confidence\": 1}}";
    }

    /** Makes the model answer with each reply in turn, then with the last one. */
    private void stubReplies(final String first, final String... more) throws IOException
    {
        Mockito.when(this.client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
            ArgumentMatchers.any())).thenReturn(first, more);
    }

    private ArgumentCaptor<LLMRequestOptions> captureOptions(final int calls) throws IOException
    {
        final ArgumentCaptor<LLMRequestOptions> options = ArgumentCaptor.forClass(LLMRequestOptions.class);
        Mockito.verify(this.client, Mockito.times(calls)).chat(ArgumentMatchers.anyString(),
            ArgumentMatchers.anyList(), options.capture());
        return options;
    }

    @Test
    void asksEveryCallAndGathersTheAnswers() throws IOException
    {
        stubReplies(buildReply("title", "Sardines for Prediabetes"), buildReply("sponsor", "IDIBAPS"));
        final ExtractionPrompt.Plan plan = createPlan("title", "sponsor");

        final DocumentReader.Reading reading = DocumentReader.read(this.client, plan, DocumentScan.of(TEXT));

        assertEquals(List.of("title", "sponsor"), List.copyOf(reading.answers().keySet()));
        assertEquals(List.of("IDIBAPS"), reading.answers().get("sponsor").values());
        assertEquals(2, reading.outcomes().size());
        final DocumentReader.Outcome first = reading.outcomes().get(0);
        assertEquals(plan.calls().get(0), first.call());
        assertInstanceOf(DocumentReader.Answered.class, first);
        assertEquals(1, first.attempts());
    }

    @Test
    void sendsEachCallAsItWasPlanned() throws IOException
    {
        stubReplies(buildReply("title", "x"));
        final ExtractionPrompt.Call call = createPlan("title").calls().get(0);

        DocumentReader.read(this.client, createPlan("title"), DocumentScan.of(TEXT));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<LLMMessage>> messages = ArgumentCaptor.forClass(List.class);
        final ArgumentCaptor<LLMRequestOptions> options = ArgumentCaptor.forClass(LLMRequestOptions.class);
        Mockito.verify(this.client).chat(ArgumentMatchers.eq(call.systemPrompt()), messages.capture(),
            options.capture());
        assertEquals(1, messages.getValue().size());
        assertEquals("user", messages.getValue().get(0).getRole());
        assertEquals(call.userMessage(), messages.getValue().get(0).getContent());
        assertEquals(call.maxOutputTokens(), options.getValue().getMaxOutputTokens());
        assertEquals(ResponseSchema.SCHEMA_NAME, options.getValue().getResponseSchemaName());
        assertEquals(call.responseSchema(), options.getValue().getResponseSchema());
    }

    // The note goes after the questions, so the second try opens with the same text and still hits the cache
    @Test
    void asksOnceMoreAfterAReplyItCannotRead() throws IOException
    {
        stubReplies("{\"title\": {\"value\": \"Sard", buildReply("title", "Sardines"));
        final ExtractionPrompt.Call call = createPlan("title").calls().get(0);

        final DocumentReader.Reading reading =
            DocumentReader.read(this.client, createPlan("title"), DocumentScan.of(TEXT));

        assertEquals(List.of("Sardines"), reading.answers().get("title").values());
        assertInstanceOf(DocumentReader.Answered.class, reading.outcomes().get(0));
        assertEquals(2, reading.outcomes().get(0).attempts());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<LLMMessage>> messages = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.client, Mockito.times(2)).chat(ArgumentMatchers.anyString(), messages.capture(),
            ArgumentMatchers.any());
        final String retry = messages.getAllValues().get(1).get(0).getContent();
        assertTrue(retry.startsWith(call.userMessage()), retry);
        assertTrue(retry.endsWith("Your previous reply could not be read: it was cut off before the end. Reply with"
            + " one JSON object that follows the schema, and nothing else."), retry);
    }

    @Test
    void givesUpOnACallAfterTwoBadRepliesAndGoesOn() throws IOException
    {
        stubReplies("I cannot help with that.", "Still no.", buildReply("sponsor", "IDIBAPS"));

        final DocumentReader.Reading reading = DocumentReader.read(this.client, createPlan("title", "sponsor"),
            DocumentScan.of(TEXT));

        assertEquals(List.of("sponsor"), List.copyOf(reading.answers().keySet()));
        final DocumentReader.Outcome lost = reading.outcomes().get(0);
        assertInstanceOf(DocumentReader.Unreadable.class, lost);
        assertEquals(2, lost.attempts());
        assertEquals("it held no JSON object", ((DocumentReader.Unreadable) lost).problem());
        assertInstanceOf(DocumentReader.Answered.class, reading.outcomes().get(1));
    }

    // The calls after an outage would each wait out the same timeout
    @Test
    void stopsAskingOnceTheModelCannotBeReached() throws IOException
    {
        final IOException outage = new IOException("connection refused");
        Mockito.when(this.client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
            ArgumentMatchers.any())).thenThrow(outage);

        final DocumentReader.Reading reading = DocumentReader.read(this.client, createPlan("title", "sponsor"),
            DocumentScan.of(TEXT));

        captureOptions(1);
        assertTrue(reading.outcomes().stream().allMatch(DocumentReader.Failed.class::isInstance));
        assertEquals(List.of(1, 0), reading.outcomes().stream().map(DocumentReader.Outcome::attempts).toList());
        assertSame(outage, reading.findFailure());
        assertTrue(reading.answers().isEmpty());
        assertNull(reading.findProblem());
    }

    // Half the questions get the whole call's room, so each has twice as much
    @Test
    void asksAReplyThatRanOutOfRoomAgainInTwoHalves() throws IOException
    {
        stubReplies("{\"title\": {\"evidence\": [{\"quote\": \"x\"}], \"value\": \"Sard",
            buildReply("title", "Sardines"), buildReply("sponsor", "IDIBAPS"));
        final ExtractionPrompt.Plan plan = createOneCallPlan("title", "sponsor");

        final DocumentReader.Reading reading = DocumentReader.read(this.client, plan, DocumentScan.of(TEXT));

        assertEquals(List.of("title", "sponsor"), List.copyOf(reading.answers().keySet()));
        assertEquals(2, reading.outcomes().size());
        final List<LLMRequestOptions> options = captureOptions(3).getAllValues();
        assertEquals(plan.calls().get(0).maxOutputTokens(), options.get(2).getMaxOutputTokens());
    }

    // The whole call's schema requires every key, which a half does not ask
    @Test
    void sendsEachHalfWithItsOwnSchema() throws IOException
    {
        stubReplies("{\"title\": {\"evidence\": [{\"quote\": \"x\"}], \"value\": \"Sard",
            buildReply("title", "Sardines"), buildReply("sponsor", "IDIBAPS"));

        DocumentReader.read(this.client, createOneCallPlan("title", "sponsor"), DocumentScan.of(TEXT));

        final List<LLMRequestOptions> options = captureOptions(3).getAllValues();
        assertEquals(ResponseSchema.build(List.of(TestFields.createTextField("title"))),
            options.get(1).getResponseSchema());
        assertEquals(ResponseSchema.build(List.of(TestFields.createTextField("sponsor"))),
            options.get(2).getResponseSchema());
    }

    // The second half would wait out the same timeout
    @Test
    void stopsAskingTheHalvesOnceTheModelCannotBeReached() throws IOException
    {
        final IOException outage = new IOException("connection refused");
        Mockito.when(this.client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
            ArgumentMatchers.any())).thenReturn("{\"title\": {\"evidence\": [{\"quote\": \"x\"}], \"value\": \"Sard")
            .thenThrow(outage);

        final DocumentReader.Reading reading =
            DocumentReader.read(this.client, createOneCallPlan("title", "sponsor"), DocumentScan.of(TEXT));

        captureOptions(2);
        assertEquals(List.of(1, 0), reading.outcomes().stream().map(DocumentReader.Outcome::attempts).toList());
        assertSame(outage, reading.findFailure());
    }

    // A reply that breaks the reader costs its own call only, like any reply that cannot be read
    @Test
    void treatsAReplyThatBreaksTheReaderAsUnreadable() throws IOException
    {
        final ExtractionPrompt.Plan plan = createPlan("title");
        final DocumentScan document = Mockito.mock(DocumentScan.class);
        Mockito.when(document.locate(ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("broken"));
        stubReplies("{\"title\": {\"evidence\": [{\"quote\": \"Sardines\"}], \"value\": \"x\"}}");

        final DocumentReader.Reading reading = DocumentReader.read(this.client, plan, document);

        assertInstanceOf(DocumentReader.Unreadable.class, reading.outcomes().get(0));
        assertEquals("it could not be read", reading.findProblem());
    }
}
