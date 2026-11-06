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
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.httprequests.api.Multipart;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TransferSubmissionHandler}: what it sends where, and how it reads the answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TransferSubmissionHandlerTest
{
    private static final String DESTINATION = "https://site-b.example.org/";

    private static final String ENDPOINT = "https://site-b.example.org/system/remote/submissions";

    private static final byte[] SCAN = {0x25, 0x50, 0x44, 0x46};

    // A real repository: the submitter's email is read from their account
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final TransferSubmissionHandler handler = new TransferSubmissionHandler();

    private final SubmissionExporter exporter = Mockito.mock(SubmissionExporter.class);

    private final TokenManager tokens = Mockito.mock(TokenManager.class);

    private final HttpRequests http = Mockito.mock(HttpRequests.class);

    private final JsonObject manifest = Json.createObjectBuilder().add("version", 1).build();

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        inject("exporter", this.exporter);
        inject("tokens", this.tokens);
        inject("http", this.http);
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final User alice = ((JackrabbitSession) session).getUserManager().createUser("alice", "password");
        alice.setProperty("profile/email", session.getValueFactory().createValue("alice@example.org"));
        ((JackrabbitSession) session).getUserManager().createUser("carol", "password");
        session.save();
        this.submission = this.context.create().resource("/Submissions/s1", Map.of("createdBy", "alice"));
        final Binary binary = Mockito.mock(Binary.class);
        Mockito.when(binary.getStream()).thenAnswer(invocation -> new ByteArrayInputStream(SCAN));
        final Map<String, Binary> binaries = new LinkedHashMap<>();
        binaries.put("b0", binary);
        Mockito.when(this.exporter.export(this.submission)).thenReturn(new SubmissionExport(this.manifest, binaries));
        Mockito.when(this.tokens.isTrustedPeer(DESTINATION)).thenReturn(true);
        final IapToken token = Mockito.mock(IapToken.class);
        Mockito.when(token.getToken()).thenReturn("signed");
        Mockito.when(this.tokens.create(Mockito.eq("alice"), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(token);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("transferSubmission", this.handler.getName());
    }

    @Test
    void sendsTheSubmissionWithATokenForTheDestination() throws Exception
    {
        answer(201, "{\"path\": \"/Submissions/s1\"}");

        this.handler.execute(context(DESTINATION));

        final ArgumentCaptor<Calendar> expiry = ArgumentCaptor.forClass(Calendar.class);
        Mockito.verify(this.tokens).create(Mockito.eq("alice"), expiry.capture(),
            Mockito.eq(Map.of("email", "alice@example.org")), Mockito.eq(Set.of(DESTINATION)));
        assertTrue(expiry.getValue().after(Calendar.getInstance()));
        final ArgumentCaptor<Multipart> body = ArgumentCaptor.forClass(Multipart.class);
        Mockito.verify(this.http).post(Mockito.eq(ENDPOINT), Mockito.eq(Map.of("Authorization", "Bearer signed")),
            body.capture());
        final List<Multipart.Part> parts = body.getValue().getParts();
        assertEquals("manifest", parts.get(0).name());
        assertEquals(this.manifest.toString(), text(parts.get(0).content().open()));
        // A binary goes as a file, which is what makes the other end take it as an attachment
        assertEquals("b0", parts.get(1).name());
        assertEquals("b0", parts.get(1).filename());
        try (InputStream scan = parts.get(1).content().open()) {
            assertEquals(ByteBuffer.wrap(SCAN), ByteBuffer.wrap(scan.readAllBytes()));
        }
    }

    @Test
    void aSubmissionTheDestinationAlreadyHasFromHereCountsAsSent() throws Exception
    {
        answer(409, "{\"reason\": \"alreadyReceived\", \"error\": \"Already here\"}");
        // Registered without a final slash this time, which reaches the same endpoint
        Mockito.when(this.tokens.isTrustedPeer("https://site-b.example.org")).thenReturn(true);

        this.handler.execute(context("https://site-b.example.org"));

        Mockito.verify(this.http).post(Mockito.eq(ENDPOINT), Mockito.anyMap(), Mockito.any(Multipart.class));
    }

    @Test
    void anyOtherConflictIsRefusedInTheDestinationsWords() throws Exception
    {
        answer(409, "{\"error\": \"Nobody here has that email\"}");

        final InvalidStateException refusal =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(context(DESTINATION)));
        assertEquals(DESTINATION + " did not take the submission: Nobody here has that email", refusal.getMessage());
    }

    @Test
    void aRequestTheDestinationCannotUseIsABadPayload() throws Exception
    {
        // A proxy in the way answers in its own words, which are not JSON
        answer(413, "<html>Too large</html>");

        final InvalidPayloadException refusal =
            assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(DESTINATION)));
        assertEquals(DESTINATION + " did not take the submission: 413", refusal.getMessage());
    }

    @Test
    void aDestinationThatFailsFailsTheTransfer() throws Exception
    {
        answer(503, "");

        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context(DESTINATION)));
    }

    @Test
    void aDestinationThatCannotBeReachedFailsTheTransfer() throws Exception
    {
        Mockito.when(this.http.post(Mockito.anyString(), Mockito.anyMap(), Mockito.any(Multipart.class)))
            .thenThrow(new IOException("Connection refused"));

        final WorkflowFailedException failure =
            assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context(DESTINATION)));
        assertEquals("Could not send the submission to " + DESTINATION, failure.getMessage());
    }

    @Test
    void anInstanceThisOneDoesNotTrustIsRefused() throws Exception
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context("https://elsewhere.example.org")));
        Mockito.verifyNoInteractions(this.http);
    }

    @Test
    void aDestinationIsRequired()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(new WorkflowEvent("transfer", Map.of()))));
    }

    @Test
    void aSubmissionWithoutASubmitterIsRefused()
    {
        this.submission = this.context.create().resource("/Submissions/s2");

        assertThrows(InvalidStateException.class, () -> this.handler.execute(context(DESTINATION)));
    }

    @Test
    void aSubmitterWithoutAnEmailIsRefused()
    {
        this.submission = this.context.create().resource("/Submissions/s3", Map.of("createdBy", "carol"));

        assertThrows(InvalidStateException.class, () -> this.handler.execute(context(DESTINATION)));
        Mockito.verifyNoInteractions(this.http);
    }

    @Test
    void anInstanceThatCannotIssueTokensCannotSend()
    {
        Mockito.when(this.tokens.create(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(null);

        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context(DESTINATION)));
    }

    @Test
    void aSubmissionThatCannotBeReadFailsTheTransfer() throws Exception
    {
        Mockito.when(this.exporter.export(this.submission)).thenThrow(new RepositoryException("Gone"));

        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context(DESTINATION)));
    }

    @Test
    void aBinaryThatCannotBeReadFailsTheRequest() throws Exception
    {
        final Binary broken = Mockito.mock(Binary.class);
        Mockito.when(broken.getStream()).thenThrow(new RepositoryException("Blob missing"));
        Mockito.when(this.exporter.export(this.submission))
            .thenReturn(new SubmissionExport(this.manifest, Map.of("b0", broken)));
        answer(201, "{}");

        this.handler.execute(context(DESTINATION));

        final ArgumentCaptor<Multipart> body = ArgumentCaptor.forClass(Multipart.class);
        Mockito.verify(this.http).post(Mockito.anyString(), Mockito.anyMap(), body.capture());
        assertThrows(IOException.class, () -> body.getValue().getParts().get(1).content().open());
    }

    private void answer(final int status, final String body) throws IOException
    {
        Mockito.when(this.http.post(Mockito.anyString(), Mockito.anyMap(), Mockito.any(Multipart.class)))
            .thenReturn(new HttpResponse(status, body));
    }

    private WorkflowTaskContext context(final String destination)
    {
        return context(new WorkflowEvent("transfer", Map.of("destination", destination)));
    }

    private WorkflowTaskContext context(final WorkflowEvent event)
    {
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getTarget()).thenReturn(this.submission);
        Mockito.when(task.getEvent()).thenReturn(event);
        return task;
    }

    private void inject(final String name, final Object value) throws ReflectiveOperationException
    {
        final Field field = TransferSubmissionHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.handler, value);
    }

    private static String text(final InputStream content) throws IOException
    {
        try (content) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(content.readAllBytes())).toString();
        }
    }
}
