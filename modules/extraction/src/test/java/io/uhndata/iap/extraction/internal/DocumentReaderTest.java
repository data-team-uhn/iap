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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static ExtractionField field(final String key)
    {
        return new ExtractionField(key, "Q?", null, null, "Find it.",
            new ExtractionField.Answer("text", false, null, null, null, List.of()));
    }

    private static ExtractionPrompt.Plan plan(final String... groups) throws IOException
    {
        return ExtractionPrompt.plan(TEXT, Arrays.stream(groups)
            .map(group -> new ExtractionPrompt.Group(group, List.of(field(group)))).toList(), 200_000L);
    }

    private static String answer(final String key, final String value)
    {
        return "{\"" + key + "\": {\"evidence\": [], \"reasoning\": \"\", \"value\": \"" + value
            + "\", \"confidence\": 1}}";
    }

    @Test
    void asksEveryCallAndGathersTheAnswers() throws IOException
    {
        final LLMClient client = Mockito.mock(LLMClient.class);
        Mockito.when(client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenReturn(answer("title", "Sardines for Prediabetes"), answer("sponsor", "IDIBAPS"));
        final ExtractionPrompt.Plan plan = plan("title", "sponsor");

        final DocumentReader.Reading reading = DocumentReader.read(client, plan, DocumentScan.of(TEXT));

        assertEquals(List.of("title", "sponsor"), List.copyOf(reading.answers().keySet()));
        assertEquals(List.of("IDIBAPS"), reading.answers().get("sponsor").values());
        assertEquals(2, reading.outcomes().size());
        final DocumentReader.Outcome first = reading.outcomes().get(0);
        assertEquals(plan.calls().get(0), first.call());
        assertEquals(DocumentReader.Status.ANSWERED, first.status());
        assertEquals(1, first.attempts());
        assertNull(first.problem());
    }

    @Test
    void sendsEachCallAsItWasPlanned() throws IOException
    {
        final LLMClient client = Mockito.mock(LLMClient.class);
        Mockito.when(client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenReturn(answer("title", "x"));
        final ExtractionPrompt.Call call = plan("title").calls().get(0);

        DocumentReader.read(client, plan("title"), DocumentScan.of(TEXT));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<LLMMessage>> messages = ArgumentCaptor.forClass(List.class);
        final ArgumentCaptor<LLMRequestOptions> options = ArgumentCaptor.forClass(LLMRequestOptions.class);
        Mockito.verify(client).chat(ArgumentMatchers.eq(call.systemPrompt()), messages.capture(), options.capture());
        assertEquals(1, messages.getValue().size());
        assertEquals("user", messages.getValue().get(0).getRole());
        assertEquals(call.userMessage(), messages.getValue().get(0).getContent());
        assertEquals(call.maxOutputTokens(), options.getValue().getMaxOutputTokens());
        assertEquals(ResponseSchema.NAME, options.getValue().getResponseSchemaName());
        assertEquals(call.responseSchema(), options.getValue().getResponseSchema());
    }

    @Test
    void asksOnceMoreAfterAReplyItCannotRead() throws IOException
    {
        final LLMClient client = Mockito.mock(LLMClient.class);
        Mockito.when(client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenReturn("{\"title\": {\"value\": \"Sard", answer("title", "Sardines"));
        final ExtractionPrompt.Call call = plan("title").calls().get(0);

        final DocumentReader.Reading reading = DocumentReader.read(client, plan("title"), DocumentScan.of(TEXT));

        assertEquals(List.of("Sardines"), reading.answers().get("title").values());
        assertEquals(DocumentReader.Status.ANSWERED, reading.outcomes().get(0).status());
        assertEquals(2, reading.outcomes().get(0).attempts());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<LLMMessage>> messages = ArgumentCaptor.forClass(List.class);
        Mockito.verify(client, Mockito.times(2)).chat(ArgumentMatchers.anyString(), messages.capture(),
            ArgumentMatchers.any());
        final String retry = messages.getAllValues().get(1).get(0).getContent();
        assertTrue(retry.startsWith(call.userMessage()), retry);
        assertTrue(retry.endsWith("Your previous reply could not be read: it was cut off before the end. Reply with"
            + " one JSON object that follows the schema, and nothing else."), retry);
    }

    @Test
    void givesUpOnACallAfterTwoBadRepliesAndGoesOn() throws IOException
    {
        final LLMClient client = Mockito.mock(LLMClient.class);
        Mockito.when(client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenReturn("I cannot help with that.", "Still no.", answer("sponsor", "IDIBAPS"));

        final DocumentReader.Reading reading = DocumentReader.read(client, plan("title", "sponsor"),
            DocumentScan.of(TEXT));

        assertEquals(List.of("sponsor"), List.copyOf(reading.answers().keySet()));
        final DocumentReader.Outcome lost = reading.outcomes().get(0);
        assertEquals(DocumentReader.Status.UNREADABLE, lost.status());
        assertEquals(2, lost.attempts());
        assertEquals("it held no JSON object", lost.problem());
        assertEquals(DocumentReader.Status.ANSWERED, reading.outcomes().get(1).status());
    }

    @Test
    void recordsACallTheModelCouldNotTakeAndGoesOn() throws IOException
    {
        final LLMClient client = Mockito.mock(LLMClient.class);
        Mockito.when(client.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenThrow(new IOException("The model could not be reached")).thenReturn(answer("sponsor", "IDIBAPS"));

        final DocumentReader.Reading reading = DocumentReader.read(client, plan("title", "sponsor"),
            DocumentScan.of(TEXT));

        final DocumentReader.Outcome failed = reading.outcomes().get(0);
        assertEquals(DocumentReader.Status.FAILED, failed.status());
        assertEquals(1, failed.attempts());
        assertEquals("The model could not be reached", failed.problem());
        assertEquals(List.of("sponsor"), List.copyOf(reading.answers().keySet()));
    }
}
