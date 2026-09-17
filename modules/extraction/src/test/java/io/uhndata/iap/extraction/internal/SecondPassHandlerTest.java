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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SecondPassHandler}: which questions it asks again, and what it does with the answers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SecondPassHandlerTest
{
    private static final String AIMS = "study/aims";

    private static final String ANSWER_TYPE = "jcr:primaryType";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SecondPassService service = Mockito.mock(SecondPassService.class);

    private final SecondPassHandler handler = new SecondPassHandler();

    private final Map<String, Object> variables = new HashMap<>();

    private SubmissionTree tree;

    private Resource submission;

    private Resource file;

    private Resource aims;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.aims = this.tree.question("aims", "Find the primary aims.", "B.3");
        this.tree.question("comments", null);
        this.submission = this.tree.submission();
        this.file = this.tree.file("completed");
        final Field field = SecondPassHandler.class.getDeclaredField("secondPass");
        field.setAccessible(true);
        field.set(this.handler, this.service);
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "PROPOSAL");
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath());
    }

    private WorkflowTaskContext task()
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables);
    }

    /** An answer the first pass wrote, with the extraction under it saying how it went. */
    private void extracted(final String suffix, final double confidence, final boolean again)
    {
        final Resource answer = this.context.create().resource(this.submission.getPath() + "/a" + suffix,
            Map.of(ANSWER_TYPE, "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "what the model read" }));
        SubmissionTree.reference(answer, "question", this.aims);
        this.context.create().resource(answer.getPath() + "/e1",
            Map.of(ANSWER_TYPE, "sub:Extraction", "sling:resourceType", "sub/Extraction",
                "extractedAnswer", "what the model read", "confidence", confidence,
                "needsSecondLook", again));
    }

    private List<Resource> answers()
    {
        final List<Resource> answers = new ArrayList<>();
        for (final Resource child : this.submission.getChildren()) {
            if ("sub:Answer".equals(child.getValueMap().get(ANSWER_TYPE, String.class))) {
                answers.add(child);
            }
        }
        return answers;
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("secondPass", this.handler.getName());
    }

    @Test
    void doesNothingForADocumentTheGateTurnedAway() throws Exception
    {
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "NOT_PROPOSAL");

        this.handler.execute(task());

        Mockito.verifyNoInteractions(this.service);
    }

    @Test
    void doesNothingWhenTheFileTheGateReadIsGone() throws Exception
    {
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath() + "-gone");

        this.handler.execute(task());

        Mockito.verifyNoInteractions(this.service);
    }

    // The first pass answered it well enough, so there is nothing to ask again
    @Test
    void doesNothingWhenEveryFieldIsSettled() throws Exception
    {
        extracted("1", 0.95, false);

        this.handler.execute(task());

        Mockito.verifyNoInteractions(this.service);
    }

    @Test
    void asksAgainForAFieldNothingAnswered() throws Exception
    {
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(Map.of());

        this.handler.execute(task());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.service).run(Mockito.any(), Mockito.any(), fields.capture());
        assertEquals(List.of(AIMS), fields.getValue().stream().map(ExtractionField::name).toList(),
            "the question with no prompt is the submitter's to answer");
    }

    @Test
    void asksAgainForAFieldTheFirstPassFlagged() throws Exception
    {
        extracted("1", 0.95, true);
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(Map.of());

        this.handler.execute(task());

        Mockito.verify(this.service).run(Mockito.any(), Mockito.any(), Mockito.anyList());
    }

    @Test
    void asksAgainForAnAnswerNobodyIsSureOf() throws Exception
    {
        extracted("1", 0.4, false);
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(Map.of());

        this.handler.execute(task());

        Mockito.verify(this.service).run(Mockito.any(), Mockito.any(), Mockito.anyList());
    }

    // What a person said stands over what a model read
    @Test
    void leavesAQuestionTheSubmitterAnsweredAlone() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of(ANSWER_TYPE, "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);

        this.handler.execute(task());

        Mockito.verifyNoInteractions(this.service);
    }

    @Test
    void writesWhatTheSecondPassFound() throws Exception
    {
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(
            Map.of(AIMS, new FieldResult(AIMS, true, 0.9, "found at last", "Stated in the appendix.",
                List.of())));

        this.handler.execute(task());

        assertEquals(1, answers().size());
        assertArrayEquals(new String[] { "found at last" },
            answers().get(0).getValueMap().get("value", String[].class));
    }

    @Test
    void writesNothingWhenTheSecondPassFoundNothing() throws Exception
    {
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(
            Map.of(AIMS, new FieldResult(AIMS, false, 0.0, null, "Still not stated.", List.of())));

        this.handler.execute(task());

        assertTrue(answers().isEmpty());
    }

    // The first pass's answers stand and the submitter fills in the rest
    @Test
    void doesNotFailTheRunWhenTheModelCannotBeReached() throws Exception
    {
        Mockito.when(this.service.run(Mockito.any(), Mockito.any(), Mockito.anyList()))
            .thenThrow(new IOException("the model is unreachable"));

        this.handler.execute(task());

        assertTrue(answers().isEmpty());
    }
}
