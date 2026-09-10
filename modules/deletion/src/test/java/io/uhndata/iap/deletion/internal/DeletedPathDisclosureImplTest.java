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
package io.uhndata.iap.deletion.internal;

import java.lang.reflect.Field;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.scripting.DeletedPathDisclosure.Disclosure;
import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DeletedPathDisclosureImpl}.
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
class DeletedPathDisclosureImplTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private DeletedPathDisclosureImpl disclosure;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/deletion.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "del:Archive");
        this.session.save();
        this.disclosure = disclosureWith(new TestResolverFactory(this.context.resourceResolver()));
    }

    /** A component holding one service resolver factory, wired the way SCR would wire it. */
    private static DeletedPathDisclosureImpl disclosureWith(final ResourceResolverFactory factory) throws Exception
    {
        final DeletedPathDisclosureImpl component = new DeletedPathDisclosureImpl();
        final Field field = DeletedPathDisclosureImpl.class.getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(component, factory);
        return component;
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
    private Disclosure about(final String path)
    {
        return this.about(path, this.context.resourceResolver());
    }

    /** A reader the archive is invisible to, which is everyone the archive has not been granted to. */
    private Disclosure aboutAsOrdinaryUser(final String path)
    {
        return this.about(path, hidingTheArchive(this.context.resourceResolver()));
    }

    private Disclosure about(final String path, final ResourceResolver resolver)
    {
        return this.disclosure.describe(
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext()), path);
    }

    /** A resolver that cannot see the archive, the way an ordinary reader's cannot. */
    private static ResourceResolver hidingTheArchive(final ResourceResolver real)
    {
        return new ResourceResolverWrapper(real)
        {
            @Override
            public Resource getResource(final String requested)
            {
                return requested.startsWith("/Archive") ? null : super.getResource(requested);
            }
        };
    }

    @Test
    void aPathNobodyDeletedIsNotReportedAsDeleted() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        assertNull(this.about("/Submissions/two"));
    }

    @Test
    void anOrdinaryReaderLearnsThatItWasDeletedAndWhen() throws Exception
    {
        final Node entry = this.entry("one", "alice", "/Submissions/one");

        final Disclosure told = this.aboutAsOrdinaryUser("/Submissions/one");

        assertNotNull(told);
        assertEquals(DateUtils.toString(entry.getProperty("jcr:created").getDate()), told.deletedAt());
        // Who deleted it, and where it now is, are not theirs to know
        assertNull(told.deletedBy());
        assertNull(told.entryUrl());
    }

    @Test
    void aReaderOfTheArchiveAlsoLearnsWhoDeletedItAndWhereToLook() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        final Disclosure told = this.about("/Submissions/one");

        assertNotNull(told);
        assertEquals("alice", told.deletedBy());
        assertEquals("/admin/archive/one", told.entryUrl());
    }

    @Test
    void theRequestUriIsAskedAboutAsResourceResolutionWouldHaveSplitIt() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        assertNotNull(this.about("/Submissions/one.html"));
    }

    @Test
    void aPathInsideADeletedSubtreeIsAnsweredAgainstTheSubtree() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        final Disclosure told = this.about("/Submissions/one/answers/first");

        assertNotNull(told);
        assertEquals("/admin/archive/one", told.entryUrl());
    }

    @Test
    void aDeletionWithNoUsableDateIsNotReportedAsOne()
    {
        // Reached directly, because the date it stands in for is mandatory on the node type: a query can never
        // return a match without one, and this is what keeps a broken repository from being read as a live path
        final DeletedPathLookup.Archived undated =
            new DeletedPathLookup.Archived("/Submissions/one", "/Archive/ab/one", "one", "alice", null);

        assertNull(this.disclosure.disclose(undated,
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext())));
    }

    @Test
    void theRootHasNoAncestorToAskAbout()
    {
        assertNull(this.about("/"));
    }

    @Test
    void anUnavailableServiceUserIsReportedRatherThanThrowing() throws Exception
    {
        this.disclosure = disclosureWith(new TestResolverFactory(null));

        assertNull(this.about("/Submissions/one"));
    }

    @Test
    void aServiceResolverWithNoRepositoryBehindItIsReportedRatherThanThrowing() throws Exception
    {
        this.disclosure = disclosureWith(new TestResolverFactory(new ResourceResolverWrapper(
            this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return Session.class.equals(type) ? null : super.adaptTo(type);
            }
        }));

        assertNull(this.about("/Submissions/one"));
    }

    @Test
    void aServiceSessionThatCannotSeeTheArchiveIsReportedRatherThanReadAsNothingDeleted() throws Exception
    {
        // The failure this sentinel exists for: a session that logs in but is not the one this component asked
        // for, and so sees no archive. An absent archive stands in for it here, because access control is what
        // hides it in the real case and this repository has none — what is being exercised is the branch that
        // reports it. repoinit creates the archive, so not seeing it can only ever mean the wrong session, and
        // left unreported it would make every dead link claim it had never been a link at all.
        this.session.getNode("/Archive").remove();
        this.session.save();

        assertNull(this.about("/Submissions/one"));
    }
}
