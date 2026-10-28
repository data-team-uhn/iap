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
package io.uhndata.iap.submissions.internal.handlers;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SubmissionHost}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionHostTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void passesASubmissionThrough() throws WorkflowDefinitionException
    {
        final Resource submission = this.context.create().resource("/Submissions/ab/cd/ef/aRequest",
            "sling:resourceType", Submission.RESOURCE_TYPE);

        assertSame(submission, SubmissionHost.require(submission, "aTask"));
    }

    @Test
    void readsASubmissionAsItsModel() throws WorkflowDefinitionException
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Submission.class);
        final Resource submission = this.context.create().resource("/Submissions/ab/cd/ef/aRequest",
            "sling:resourceType", Submission.RESOURCE_TYPE, "title", "A long weekend");

        assertEquals("A long weekend", SubmissionHost.read(submission, "aTask").getTitle());
    }

    @Test
    void refusesAnythingElseNamingTheTaskAndWhatItWasPointedAt()
    {
        final Resource homepage = this.context.create().resource("/Submissions",
            "sling:resourceType", "sub/SubmissionsHomepage");

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> SubmissionHost.read(homepage, "aTask"));

        assertEquals("The aTask task only applies to submissions, not to sub/SubmissionsHomepage",
            refusal.getMessage());
    }
}
