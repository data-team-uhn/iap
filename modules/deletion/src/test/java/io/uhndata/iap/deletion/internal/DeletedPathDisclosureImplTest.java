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
import io.uhndata.iap.utils.UserIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DeletedPathDisclosureImpl}.
 *
 * <p>
 * What a reader is told turns on two questions: whether their own resolver can read the archive entry, and whether
 * they deleted it. The test's own resolver bypasses access control, and reads the entry. The other readers use a
 * wrapper that hides the archive, as Oak hides it from everyone it is not granted to.
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
    void somebodyWhoNeitherDeletedItNorReadsTheArchiveIsToldNothing() throws Exception
    {
        this.entry("one", "alice", "/Submissions/one");

        assertNull(this.aboutAsOrdinaryUser("/Submissions/one"));
    }

    @Test
    void theDeleterLearnsWhenItWentAndIsOfferedNoArchiveLink() throws Exception
    {
        final Node entry =
            this.entry("one", UserIds.canonical(this.context.resourceResolver()), "/Submissions/one");

        final Disclosure told = this.aboutAsOrdinaryUser("/Submissions/one");

        assertNotNull(told);
        assertEquals(DateUtils.toString(entry.getProperty("jcr:created").getDate()), told.deletedAt());
        // They cannot open the archive, so there is nothing to send them to
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
        // Called directly: every entry has a jcr:created, so a query never finds one without it
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
        // A session that logs in and sees no archive. Removing the archive stands in for access control,
        // which this repository does not enforce.
        this.session.getNode("/Archive").remove();
        this.session.save();

        assertNull(this.about("/Submissions/one"));
    }
}
