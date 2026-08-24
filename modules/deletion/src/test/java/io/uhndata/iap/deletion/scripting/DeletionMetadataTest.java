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
package io.uhndata.iap.deletion.scripting;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.RequestDispatcher;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.scripting.DeletedPathDisclosure.Disclosure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeletionMetadata}.
 *
 * <p>
 * What is left in this class once the archive is somebody else's problem is the request: which path the error
 * dispatch was about, and how the answer reaches the page. The disclosure service is therefore a stand-in that
 * records what it was asked about, which is what the encoding tests assert against.
 * </p>
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DeletionMetadataTest
{
    private static final Disclosure DELETED = new Disclosure("2026-08-20T14:00:00.000-04:00", "alice",
        "/admin/archive/one");

    private final SlingContext context = new SlingContext();

    /** The paths the page asked the archive about, in order. */
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setup()
    {
        this.context.addModelsForClasses(DeletionMetadata.class);
    }

    /** Register a stand-in archive that always answers the same way, and records what it was asked. */
    private void serving(final Disclosure answer)
    {
        this.context.registerService(DeletedPathDisclosure.class, (request, path) -> {
            this.asked.add(path);
            return answer;
        });
    }

    /** Adapt the model the way HTL's provider does, from the request the error handler is running on. */
    private DeletionMetadata render()
    {
        final DeletionMetadata metadata = this.context.jakartaRequest().adaptTo(DeletionMetadata.class);
        assertNotNull(metadata, "The model should always be adaptable from a request");
        return metadata;
    }

    /** A request the error dispatch recorded a URI for, which is how a real 404 arrives. */
    private DeletionMetadata about(final String uri, final Disclosure answer)
    {
        this.serving(answer);
        this.context.jakartaRequest().setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        return this.render();
    }

    /** Nothing to say: the three getters a page reads are all absent, so it renders a plain not-found. */
    private static void assertSaysNothing(final DeletionMetadata metadata)
    {
        assertNull(metadata.getDeletedAt());
        assertNull(metadata.getDeletedBy());
        assertNull(metadata.getEntryUrl());
    }

    @Test
    void aDeletedPathIsReportedWithEverythingTheReaderMayKnow()
    {
        final DeletionMetadata metadata = this.about("/Submissions/one", DELETED);

        assertEquals("2026-08-20T14:00:00.000-04:00", metadata.getDeletedAt());
        assertEquals("alice", metadata.getDeletedBy());
        assertEquals("/admin/archive/one", metadata.getEntryUrl());
    }

    @Test
    void aReaderWhoMayNotSeeTheArchiveIsToldOnlyWhen()
    {
        final DeletionMetadata metadata =
            this.about("/Submissions/one", new Disclosure("2026-08-20T14:00:00.000-04:00", null, null));

        assertEquals("2026-08-20T14:00:00.000-04:00", metadata.getDeletedAt());
        assertNull(metadata.getDeletedBy());
        assertNull(metadata.getEntryUrl());
    }

    @Test
    void aPathNobodyDeletedIsNotReportedAsDeleted()
    {
        assertSaysNothing(this.about("/Submissions/one", null));

        assertEquals(List.of("/Submissions/one"), this.asked);
    }

    @Test
    void theEncodingTheRequestUriCarriesIsUndoneBeforeAsking()
    {
        // An error handler is handed the request URI, which is still percent-encoded; no second layer is involved,
        // because nothing had to carry the path through a query string to get here
        this.about("/Submissions/one%20two", null);

        assertEquals(List.of("/Submissions/one two"), this.asked);
    }

    @Test
    void aPlusInAPathIsAPlus()
    {
        // The character the form decoder and the URI decoder disagree about: URLDecoder would read this as a
        // space, and a browser never escapes a + in a path, so a reader cannot escape it for us
        this.about("/Submissions/a+b", null);

        assertEquals(List.of("/Submissions/a+b"), this.asked);
    }

    @Test
    void selectorsAndExtensionsAreLeftOnForTheLookupToPeel()
    {
        this.about("/Submissions/one.sel.html", null);

        assertEquals(List.of("/Submissions/one.sel.html"), this.asked);
    }

    @Test
    void aUriWhoseEscapesAreMalformedIsNotAskedAbout()
    {
        assertSaysNothing(this.about("/Submissions/%zz", DELETED));

        assertTrue(this.asked.isEmpty());
    }

    @Test
    void aUriThatIsNotAnAbsolutePathIsNotAskedAbout()
    {
        assertSaysNothing(this.about("Submissions/one", DELETED));

        assertTrue(this.asked.isEmpty());
    }

    @Test
    void aDispatchThatRecordedNoUriFallsBackToTheRequestsOwn()
    {
        // A script rendered without an error dispatch: the request's own URI is the one that failed
        this.serving(null);
        this.context.jakartaRequest().setPathInfo("/Submissions/one");

        this.render();

        assertEquals(List.of("/Submissions/one"), this.asked);
    }

    @Test
    void aPlatformWithoutTheDeletionModuleLeavesThePageAPlainNotFound()
    {
        // No disclosure service registered at all, which is what a distribution without this module looks like
        this.context.jakartaRequest().setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/Submissions/one");

        assertSaysNothing(this.render());

        assertTrue(this.asked.isEmpty());
    }
}
