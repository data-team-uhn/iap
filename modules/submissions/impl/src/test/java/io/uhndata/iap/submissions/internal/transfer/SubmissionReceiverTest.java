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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.api.WorkflowResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link SubmissionReceiver}, on a real repository: whom a submission is taken in for, and how one
 * sent again is told from one that is new.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionReceiverTest
{
    private static final String ORIGIN = "https://frontdoor.example.org";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final SubmissionReceiver receiver = new SubmissionReceiver();

    private final ResourceResolverFactory resolverFactory = Mockito.mock(ResourceResolverFactory.class);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final String name = UUID.randomUUID().toString();

    private final EventAttachment scan = Mockito.mock(EventAttachment.class);

    @BeforeEach
    void setUp() throws Exception
    {
        inject("resolverFactory", this.resolverFactory);
        inject("engine", this.engine);
        // The test's own session stands in for the peer's, and outlives each use of it
        final ResourceResolver peer = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void close()
            {
                // Closed by the test context
            }
        };
        Mockito.when(this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SubmissionReceiver.SUBSERVICE_NAME))).thenReturn(peer);
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final UserManager users = ((JackrabbitSession) session).getUserManager();
        final User alice = users.createUser("alice", "password");
        alice.setProperty("profile/email", session.getValueFactory().createValue("alice@example.org"));
        session.getRootNode().addNode("Submissions");
        session.save();
    }

    @Test
    void handsTheSubmissionToTheWorkflowsOnBehalfOfItsSubmitter() throws Exception
    {
        final String path = PrefixTree.pathFor("/Submissions", this.name);
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(new WorkflowResult(Map.of(WorkflowResult.CREATED_PATH_VARIABLE, path)));

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(),
            Map.of("b0", this.scan));

        assertEquals(201, outcome.status());
        assertEquals(path, outcome.path());
        final ArgumentCaptor<Resource> target = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(target.capture(), event.capture(), Mockito.eq("alice"));
        assertEquals("/Submissions", target.getValue().getPath());
        assertEquals("receive", event.getValue().getName());
        assertEquals(manifest(), event.getValue().get("manifest"));
        assertEquals(ORIGIN, event.getValue().get("origin"));
        assertSame(this.scan, event.getValue().get("b0"));
    }

    @Test
    void aSubmitterNobodyHereIsKnownAsIsRefused()
    {
        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "stranger@example.org", manifest(), Map.of());

        assertEquals(409, outcome.status());
        assertEquals("Nobody here is known by the submitter's email", outcome.error());
        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void aSubmissionSentAgainByTheSameInstanceIsAlreadyReceived()
    {
        existing(ORIGIN);

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(), Map.of());

        assertEquals(409, outcome.status());
        assertEquals("alreadyReceived", outcome.outcome());
        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void aSubmissionByTheSameNameFromElsewhereIsADifferentOne()
    {
        existing("https://elsewhere.example.org");

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(), Map.of());

        assertEquals(409, outcome.status());
        assertEquals("A different submission by that name is already here", outcome.error());
    }

    @Test
    void aManifestThatIsNotOneIsRefused()
    {
        assertEquals(400, this.receiver.receive(ORIGIN, "alice@example.org", "{\"version\": 1}", Map.of()).status());
        assertEquals(400, this.receiver.receive(ORIGIN, "alice@example.org", "not json", Map.of()).status());
    }

    @Test
    void theWorkflowsRefusalReachesTheSender() throws Exception
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new InvalidStateException("Not now"));

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(), Map.of());

        assertEquals(409, outcome.status());
        assertEquals("Not now", outcome.error());
    }

    @Test
    void theWorkflowsFailingIsAFailureHere() throws Exception
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowFailedException("Broken definition"));

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(), Map.of());

        assertEquals(500, outcome.status());
    }

    @Test
    void aMissingServiceUserIsAFailureHere() throws Exception
    {
        Mockito.when(this.resolverFactory.getServiceResourceResolver(Mockito.any()))
            .thenThrow(new LoginException("No such service user"));

        final ReceiveOutcome outcome = this.receiver.receive(ORIGIN, "alice@example.org", manifest(), Map.of());

        assertEquals(500, outcome.status());
        assertEquals("The submission could not be taken in", outcome.error());
    }

    private String manifest()
    {
        return "{\"version\": 1, \"root\": {\"name\": \"" + this.name + "\"}}";
    }

    private void existing(final String origin)
    {
        this.context.create().resource(PrefixTree.pathFor("/Submissions", this.name), Map.of("origin", origin));
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = SubmissionReceiver.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.receiver, value);
    }
}
