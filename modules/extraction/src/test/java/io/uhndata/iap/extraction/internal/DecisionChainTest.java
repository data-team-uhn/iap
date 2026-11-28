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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DecisionChain}: when the intake took a verdict, and when a no rejects the request.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DecisionChainTest
{
    private static final String REJECTION = "Unable to identify the proposal";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DecisionChain chain = new DecisionChain();

    private SubmissionTree tree;

    private Resource validation;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.tree.createSubmission();
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"), "A study of sardines.");
        this.validation = this.tree.createValidationRequirement("is_proposal", "proposal", "Is it one?");
    }

    @Test
    void rejectsOnANoTheIntakeTook()
    {
        this.tree.createVerdict(this.validation, "no");
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        assertEquals(REJECTION, this.chain.findRejectionMessage(getSubmission()));
        assertEquals(List.of(), this.chain.getPendingDecisions(getSubmission()));
    }

    // The model must not guess: a no it is not sure of is no rejection, even once the intake took it
    @Test
    void doesNotRejectOnANoTheModelIsUnsureOf()
    {
        this.tree.createVerdict(this.validation, "no", SubmissionTree.UNSURE);
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        assertNull(this.chain.findRejectionMessage(getSubmission()));
    }

    // The intake has not reached its task yet, so nothing tells the submitter the document was rejected
    @Test
    void doesNotRejectOnANoTheIntakeDidNotTake()
    {
        this.tree.createVerdict(this.validation, "no");

        assertNull(this.chain.findRejectionMessage(getSubmission()));
        assertEquals(List.of("is_proposal"), getPendingNames());
    }

    // An intake that asks for a new upload waits at the same task again, and the first no still stands
    @Test
    void keepsTheRejectionWhenTheIntakeWaitsAtTheTaskAgain()
    {
        this.tree.createVerdict(this.validation, "no");
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);
        this.tree.createIntakeTask("is_proposal", "created", IntakeTasks.READER_USER);

        assertEquals(REJECTION, this.chain.findRejectionMessage(getSubmission()));
    }

    @Test
    void ignoresANoOnACheckThatNoLongerApplies()
    {
        this.tree.createVerdict(this.validation, "no");
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);
        this.context.registerService(ConditionEvaluator.class, Mockito.mock(ConditionEvaluator.class));

        assertNull(this.chain.findRejectionMessage(getSubmission()));
    }

    @Test
    void countsAVerdictMadeOnlyOnceTheIntakeTookIt()
    {
        this.tree.createVerdict(this.validation, "yes");
        this.tree.createIntakeTask("is_proposal", "created", IntakeTasks.READER_USER);

        assertEquals(List.of("is_proposal"), getPendingNames());

        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        assertEquals(List.of(), getPendingNames());
    }

    // A person's task of the same name belongs to another workflow, and takes nothing from the reading
    @Test
    void doesNotCountAPersonsTaskAsTheHandOver()
    {
        this.tree.createVerdict(this.validation, "no");
        this.tree.createIntakeTask("is_proposal", "completed", "a-reviewer");

        assertNull(this.chain.findRejectionMessage(getSubmission()));
    }

    private Submission getSubmission()
    {
        return SubmissionFiles.getSubmission(
            this.context.resourceResolver().getResource(SubmissionTree.SUBMISSION_PATH));
    }

    private List<String> getPendingNames()
    {
        return this.chain.getPendingDecisions(getSubmission()).stream().map(DecisionRequirement::getName).toList();
    }
}
