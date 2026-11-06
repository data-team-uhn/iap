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
package io.uhndata.iap.submissions.internal.transfer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReceiveSubmissionHandler}: who may hand a submission over, and what is kept of where it
 * came from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReceiveSubmissionHandlerTest
{
    private static final String ORIGIN = "https://frontdoor.example.org";

    private static final String MANIFEST = "{\"version\": 1, \"root\": {}}";

    private final SlingContext context = new SlingContext();

    private final ReceiveSubmissionHandler handler = new ReceiveSubmissionHandler();

    private final SubmissionImporter importer = Mockito.mock(SubmissionImporter.class);

    private Resource home;

    private Resource received;

    @BeforeEach
    void setUp() throws Exception
    {
        final Field field = ReceiveSubmissionHandler.class.getDeclaredField("importer");
        field.setAccessible(true);
        field.set(this.handler, this.importer);
        this.home = this.context.create().resource("/Submissions");
        this.received = this.context.create().resource("/Submissions/ab/cd/s1");
        Mockito.when(this.importer.importInto(Mockito.eq(this.home), Mockito.any(), Mockito.any()))
            .thenReturn(this.received);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("receiveSubmission", this.handler.getName());
    }

    @Test
    void rebuildsTheSubmissionAndRemembersWhereItCameFrom() throws Exception
    {
        final InputStream scan = new ByteArrayInputStream(new byte[] {1, 2});
        final EventAttachment attachment = Mockito.mock(EventAttachment.class);
        Mockito.when(attachment.openStream()).thenReturn(scan);
        final WorkflowTaskContext task = context(ReceiveSubmissionHandler.PEER_USER, payload("b0", attachment));

        this.handler.execute(task);

        final ArgumentCaptor<JsonObject> manifest = ArgumentCaptor.forClass(JsonObject.class);
        final ArgumentCaptor<SubmissionImporter.Parts> parts = ArgumentCaptor.forClass(SubmissionImporter.Parts.class);
        Mockito.verify(this.importer).importInto(Mockito.eq(this.home), manifest.capture(), parts.capture());
        assertEquals(1, manifest.getValue().getInt("version"));
        assertSame(scan, parts.getValue().open("b0"));
        assertNull(parts.getValue().open("b1"), "A part that was not sent is not there");
        assertEquals(ORIGIN, this.received.getValueMap().get("origin", String.class));
        // Reported as created, so that the engine records the submitter it acted for as its creator
        Mockito.verify(task).setVariable(WorkflowResult.CREATED_PATH_VARIABLE, this.received.getPath());
        Mockito.verify(task).recordRole(this.received, "received from " + ORIGIN);
    }

    @Test
    void nobodyButTheEndpointForOtherInstancesMayHandOneOver()
    {
        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(context("alice", payload())));
        Mockito.verifyNoInteractions(this.importer);
    }

    @Test
    void theInstanceItCameFromIsRequired()
    {
        final Map<String, Object> payload = payload();
        payload.remove("origin");

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(ReceiveSubmissionHandler.PEER_USER, payload)));
    }

    @Test
    void aManifestIsRequired()
    {
        final Map<String, Object> payload = payload();
        payload.remove("manifest");

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(ReceiveSubmissionHandler.PEER_USER, payload)));
    }

    @Test
    void aManifestThatIsNotJsonIsRefused()
    {
        final Map<String, Object> payload = payload();
        payload.put("manifest", "not json");

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(ReceiveSubmissionHandler.PEER_USER, payload)));
    }

    private static Map<String, Object> payload(final Object... attachments)
    {
        final Map<String, Object> payload = new HashMap<>(Map.of("manifest", MANIFEST, "origin", ORIGIN));
        for (int i = 0; i < attachments.length; i += 2) {
            payload.put((String) attachments[i], attachments[i + 1]);
        }
        return payload;
    }

    private WorkflowTaskContext context(final String sender, final Map<String, Object> payload)
    {
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getSender()).thenReturn(sender);
        Mockito.when(task.getTarget()).thenReturn(this.home);
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent("receive", payload));
        return task;
    }
}
