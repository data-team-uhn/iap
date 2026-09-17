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
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubmissionFiles}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionFilesTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.submission = this.tree.submission();
    }

    private Submission model()
    {
        return SubmissionFiles.submission(this.submission);
    }

    @Test
    void findsTheSubmissionAFileBelongsTo()
    {
        final Resource file = this.tree.file(null);

        assertEquals(this.submission.getPath(), SubmissionFiles.submissionOf(file).getPath());
        assertEquals(this.submission.getPath(), SubmissionFiles.submissionOf(this.submission).getPath());
    }

    @Test
    void findsNoSubmissionForANodeOutsideOne()
    {
        final Resource elsewhere = this.context.create().resource("/var/somewhere", Map.of());

        assertNull(SubmissionFiles.submissionOf(elsewhere));
    }

    @Test
    void readsTheSubmission()
    {
        assertNotNull(model());
        assertEquals("A proposal", model().getTitle());
    }

    @Test
    void listsTheLatestUploadOfEveryDocumentInOrder()
    {
        final Resource first = this.tree.file("completed");
        this.tree.emptyDocument();
        final Resource third = this.tree.file(null);

        final List<File> files = SubmissionFiles.currentFiles(model());

        assertEquals(List.of(first.getPath(), third.getPath()), files.stream().map(File::getPath).toList(),
            "a document with no upload yet is skipped");
    }

    @Test
    void picksTheFirstFileWhoseParseFinished()
    {
        this.tree.file("queued");
        final Resource parsed = this.tree.file("completed");
        this.tree.file("completed");

        assertEquals(parsed.getPath(), SubmissionFiles.firstParsed(model()).getPath());
    }

    @Test
    void picksNothingWhenNoParseFinished()
    {
        this.tree.file("queued");
        this.tree.file(null);

        assertNull(SubmissionFiles.firstParsed(model()));
    }

    @Test
    void knowsWhenEveryParseHasSettled()
    {
        this.tree.file("completed");
        this.tree.file("failed");
        this.tree.file(null);

        assertTrue(SubmissionFiles.allParsesSettled(model()), "never asked for counts as settled too");
    }

    @Test
    void knowsWhenAParseIsStillGoing()
    {
        this.tree.file("completed");
        this.tree.file("active");

        assertFalse(SubmissionFiles.allParsesSettled(model()));
    }
}
