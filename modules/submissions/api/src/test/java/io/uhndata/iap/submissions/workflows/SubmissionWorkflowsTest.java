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
package io.uhndata.iap.submissions.workflows;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.testing.SystemWorkflowDefinitions;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks how the shipped submission workflows delete a submission.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionWorkflowsTest
{
    private static final String SUBMISSION = "sub/Submission";

    private static final String[] NONE = {};

    private final SlingContext context = new SlingContext();

    private SystemWorkflowDefinitions shipped;

    @BeforeEach
    void setUp() throws IOException
    {
        this.shipped = SystemWorkflowDefinitions.loadIn(this.context, SubmissionWorkflowsTest.class);
    }

    @Test
    void aSubmissionIsDeletedByItsCreatorOrAnAdministrator() throws IOException
    {
        assertEquals(Set.of("deleteSubmission"), this.shipped.answering(SUBMISSION, "delete", NONE, NONE));
        assertEquals(List.of("@creator", "iap-administrators"), this.shipped.performers("deleteSubmission"));
        assertEquals(Set.of("delete"), this.shipped.handlers("deleteSubmission"));
    }

    @Test
    void anApprovedSubmissionIsRefusedByTheDeletionVetoNotByTheWorkflow()
    {
        // The veto refuses with its reason, where a guard would only say that nothing takes the event
        assertEquals(Set.of("deleteSubmission"),
            this.shipped.answering(SUBMISSION, "delete", new String[] { "approved" }, NONE));
    }

    @Test
    void theSubmissionsHomepageIsNotDeleted()
    {
        assertEquals(Set.of(), this.shipped.answering("sub/SubmissionsHomepage", "delete", NONE, NONE));
    }
}
