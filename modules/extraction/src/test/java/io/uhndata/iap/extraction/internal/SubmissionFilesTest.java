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
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
    }

    private Submission getModel()
    {
        return SubmissionFiles.getSubmission(this.submission);
    }

    @Test
    void findsTheSubmissionAFileBelongsTo()
    {
        final Resource file = this.tree.createFile(null);

        assertEquals(this.submission.getPath(), SubmissionFiles.findSubmission(file).getPath());
        assertEquals(this.submission.getPath(), SubmissionFiles.findSubmission(this.submission).getPath());
    }

    @Test
    void findsNoSubmissionForANodeOutsideOne()
    {
        final Resource elsewhere = this.context.create().resource("/var/somewhere", Map.of());

        assertNull(SubmissionFiles.findSubmission(elsewhere));
    }

    @Test
    void readsTheSubmission()
    {
        assertNotNull(getModel());
        assertEquals("A proposal", getModel().getTitle());
    }

    @Test
    void listsTheLatestUploadOfEveryDocumentInOrder()
    {
        final String firstDocument = this.tree.createFile("completed").getParent().getParent().getPath();
        this.context.create().resource(firstDocument + "/v2", Map.of("sling:resourceType", "sub/DocumentVersion"));
        final Resource newer = this.context.create().resource(firstDocument + "/v2/file",
            Map.of("sling:resourceType", "sub/File"));
        this.tree.createEmptyDocument();
        final Resource third = this.tree.createFile(null);

        final List<File> files = SubmissionFiles.getCurrentFiles(getModel());

        assertEquals(List.of(newer.getPath(), third.getPath()), files.stream().map(File::getPath).toList(),
            "the newest version's file wins, and a document with no upload yet is skipped");
    }

    private void failWith(final Resource file, final String reason)
    {
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        assertNotNull(properties, "the fixture file has to be writable");
        properties.put("parseError", reason);
    }

    // Each case gets its own message rather than the generic fallback
    @Test
    void saysNothingHasBeenUploadedWhenThereIsNothing()
    {
        assertEquals("No document has been uploaded", SubmissionFiles.describeNothingToRead(getModel()));
    }

    // The daemon's reason names its address, paths and workers, which the submitter can do nothing about
    @Test
    void saysTheParseFailedWithoutTheDaemonsReason()
    {
        failWith(this.tree.createFile("failed"), "Calling the daemon at http://localhost:18765 failed: refused");

        assertEquals("The document could not be read. Please try again",
            SubmissionFiles.describeNothingToRead(getModel()));
    }

    @Test
    void saysTheDocumentHasNotBeenReadYetWhileAParseIsQueued()
    {
        this.tree.createFile("queued");

        assertEquals("The document has not been read yet", SubmissionFiles.describeNothingToRead(getModel()));
    }

    // Uploaded, neither queued nor failed nor finished: nothing more precise can be said than that
    @Test
    void fallsBackWhenNothingSaysWhy()
    {
        this.tree.createFile(null);

        assertEquals("No uploaded document could be read", SubmissionFiles.describeNothingToRead(getModel()));
    }

    @Test
    void picksTheFirstFileWhoseParseFinished()
    {
        this.tree.createFile("queued");
        final Resource parsed = this.tree.createFile("completed");
        this.tree.createFile("completed");

        assertEquals(parsed.getPath(), SubmissionFiles.findFirstParsed(getModel()).getPath());
    }

    @Test
    void picksNothingWhenNoParseFinished()
    {
        this.tree.createFile("queued");
        this.tree.createFile(null);

        assertNull(SubmissionFiles.findFirstParsed(getModel()));
    }

    @Test
    void knowsWhenEveryParseHasSettled()
    {
        this.tree.createFile("completed");
        this.tree.createFile("failed");
        this.tree.createFile(null);

        assertTrue(SubmissionFiles.isEveryParseSettled(getModel()), "never asked for counts as settled too");
    }

    @Test
    void knowsWhenAParseIsStillGoing()
    {
        this.tree.createFile("completed");
        this.tree.createFile("queued");

        assertFalse(SubmissionFiles.isEveryParseSettled(getModel()));
    }
}
