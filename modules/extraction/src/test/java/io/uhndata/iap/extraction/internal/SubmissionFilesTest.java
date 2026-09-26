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

import org.apache.sling.api.resource.ModifiableValueMap;
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

    private void failWith(final Resource file, final String reason)
    {
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        assertNotNull(properties, "the fixture file has to be writable");
        properties.put("parseError", reason);
    }

    // "No uploaded document could be read" is true of all three of these and useful for none of them but the
    // first, so each says what actually happened.
    @Test
    void saysNothingHasBeenUploadedWhenThereIsNothing()
    {
        assertEquals("No document has been uploaded", SubmissionFiles.whyNothingToRead(model()));
    }

    @Test
    void saysWhyTheParseFailed()
    {
        failWith(this.tree.file("failed"), "Calling the daemon at http://localhost:18765 failed: refused");

        assertEquals("The document could not be read. Calling the daemon at http://localhost:18765 failed: refused",
            SubmissionFiles.whyNothingToRead(model()));
    }

    @Test
    void saysTheParseFailedWhenItLeftNoReason()
    {
        this.tree.file("failed");

        assertEquals("The document could not be read", SubmissionFiles.whyNothingToRead(model()));
    }

    @Test
    void saysTheDocumentHasNotBeenReadYetWhileAParseIsQueued()
    {
        this.tree.file("queued");

        assertEquals("The document has not been read yet", SubmissionFiles.whyNothingToRead(model()));
    }

    // Uploaded, neither queued nor failed nor finished: nothing more precise can be said than that
    @Test
    void fallsBackWhenNothingSaysWhy()
    {
        this.tree.file(null);

        assertEquals("No uploaded document could be read", SubmissionFiles.whyNothingToRead(model()));
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
        this.tree.file("queued");

        assertFalse(SubmissionFiles.allParsesSettled(model()));
    }
}
