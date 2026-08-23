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

import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.script.Bindings;
import javax.script.SimpleBindings;

import jakarta.servlet.RequestDispatcher;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.deletion.internal.TestResolverFactory;
import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DeletionMetadata}.
 *
 * <p>
 * The two disclosure levels differ only in whether the requester's own resolver can read the archive entry, so the
 * privileged case is the plain test resolver and the ordinary case is a wrapper that hides the archive from it —
 * which is exactly what the repository does to everyone who has not been granted it.
 * </p>
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DeletionMetadataTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private ResourceResolverFactory serviceFactory;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/deletion.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "del:Archive");
        this.session.save();
        this.serviceFactory = new TestResolverFactory(this.context.resourceResolver());
    }

    private Node entry(final String name, final String deletedBy, final String originalPath) throws Exception
    {
        final Node bucket = this.session.nodeExists("/Archive/ab")
            ? this.session.getNode("/Archive/ab")
            : this.session.getNode("/Archive").addNode("ab", "del:Archive");
        final Node entry = bucket.addNode(name, "del:ArchiveEntry");
        entry.setProperty("deletedBy", deletedBy);
        entry.setProperty("requestedPath", originalPath);
        entry.addNode("item", "del:DeletedItem").setProperty("originalPath", originalPath);
        this.session.save();
        return entry;
    }

    /** A reader who can read the archive: the test's own resolver, which bypasses access control. */
    private DeletionMetadata about(final String uri)
    {
        return this.about(uri, this.context.resourceResolver());
    }

    /** A reader the archive is invisible to, which is everyone the archive has not been granted to. */
    private DeletionMetadata aboutAsOrdinaryUser(final String uri)
    {
        return this.about(uri, new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String requested)
            {
                return requested.startsWith("/Archive") ? null : super.getResource(requested);
            }
        });
    }

    private DeletionMetadata about(final String uri, final ResourceResolver resolver)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext());
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        return render(request, this.serviceFactory);
    }

    /** Run the helper the way HTL would, over the bindings a script is given. */
    private static DeletionMetadata render(final MockSlingJakartaHttpServletRequest request,
        final ResourceResolverFactory factory)
    {
        final SlingScriptHelper sling = Mockito.mock(SlingScriptHelper.class);
        Mockito.when(sling.getService(ResourceResolverFactory.class)).thenReturn(factory);
        final Bindings bindings = new SimpleBindings();
        bindings.put("jakartaRequest", request);
        bindings.put(SlingBindings.SLING, sling);
        final DeletionMetadata metadata = new DeletionMetadata();
        metadata.init(bindings);
        return metadata;
    }

    /** Nothing to say: the three getters a page reads are all absent, so it renders a plain not-found. */
    private static void assertSaysNothing(final DeletionMetadata metadata)
    {
        assertNull(metadata.getDeletedAt());
        assertNull(metadata.getDeletedBy());
        assertNull(metadata.getEntryUrl());
    }

    @Test
    void aPathNobodyDeletedIsNotReportedAsDeleted() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        assertSaysNothing(this.about("/Submissions/two"));
    }

    @Test
    void anOrdinaryReaderLearnsThatItWasDeletedAndWhen() throws Exception
    {
        final Node entry = this.entry("one", "alice", "/Submissions/one");

        final DeletionMetadata metadata = this.aboutAsOrdinaryUser("/Submissions/one");

        assertEquals(DateUtils.toString(entry.getProperty("jcr:created").getDate()), metadata.getDeletedAt());
        // Who deleted it, and where it now is, are not theirs to know
        assertNull(metadata.getDeletedBy());
        assertNull(metadata.getEntryUrl());
    }

    @Test
    void aReaderOfTheArchiveAlsoLearnsWhoDeletedItAndWhereToLook() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        final DeletionMetadata metadata = this.about("/Submissions/one");

        assertEquals("alice", metadata.getDeletedBy());
        assertEquals("/admin/archive/one", metadata.getEntryUrl());
    }

    @Test
    void theRequestUriIsAskedAboutAsResourceResolutionWouldHaveSplitIt() throws Exception
    {
        final Node entry = this.entry("one", "alice", "/Submissions/one");

        assertEquals(DateUtils.toString(entry.getProperty("jcr:created").getDate()),
            this.about("/Submissions/one.html").getDeletedAt());
    }

    @Test
    void aPathInsideADeletedSubtreeIsAnsweredAgainstTheSubtree() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        assertEquals("/admin/archive/one", this.about("/Submissions/one/answers/first").getEntryUrl());
    }

    @Test
    void theEncodingTheRequestUriCarriesIsUndone() throws Exception
    {
        // An error handler is handed the request URI, which is still percent-encoded; no second layer is involved,
        // because nothing had to carry the path through a query string to get here
        this.entry("one", "alice", "/Submissions/one two");

        assertEquals("/admin/archive/one", this.about("/Submissions/one%20two").getEntryUrl());
    }

    @Test
    void aPlusInAPathIsAPlus() throws Exception
    {
        // The character the form decoder and the URI decoder disagree about: URLDecoder would read this as a
        // space, and a browser never escapes a + in a path, so a reader cannot escape it for us
        this.entry("one", "alice", "/Submissions/a+b");

        assertEquals("/admin/archive/one", this.about("/Submissions/a+b").getEntryUrl());
    }

    @Test
    void aUriWhoseEscapesAreMalformedIsNotAskedAbout()
    {
        assertSaysNothing(this.about("/Submissions/%zz"));
    }

    @Test
    void aUriThatIsNotAnAbsolutePathIsNotAskedAbout()
    {
        assertSaysNothing(this.about("Submissions/one"));
    }

    @Test
    void aDispatchThatRecordedNoUriFallsBackToTheRequestsOwn() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        // A script rendered without an error dispatch: the request's own URI is the one that failed
        request.setPathInfo("/Submissions/one");

        assertEquals("/admin/archive/one", render(request, this.serviceFactory).getEntryUrl());
    }

    @Test
    void theRootIsNotAskedAbout()
    {
        // Nothing can have been deleted from above the root, so a 404 on it has no ancestor to ask about
        assertSaysNothing(this.about("/"));
    }

    @Test
    void anUnavailableServiceUserLeavesThePageAPlainNotFound()
    {
        this.serviceFactory = new TestResolverFactory(null);

        assertSaysNothing(this.about("/Submissions/one"));
    }

    @Test
    void aServiceResolverWithNoRepositoryBehindItLeavesThePageAPlainNotFound()
    {
        this.serviceFactory = new TestResolverFactory(new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return Session.class.equals(type) ? null : super.adaptTo(type);
            }
        });

        assertSaysNothing(this.about("/Submissions/one"));
    }

    @Test
    void aPlatformWithNoResolverFactoryLeavesThePageAPlainNotFound()
    {
        this.serviceFactory = null;

        assertSaysNothing(this.about("/Submissions/one"));
    }
}
