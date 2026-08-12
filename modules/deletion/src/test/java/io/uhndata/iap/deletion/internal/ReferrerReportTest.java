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

import java.util.List;
import java.util.stream.IntStream;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.ReferrerGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ReferrerReport}.
 *
 * @version $Id$
 */
class ReferrerReportTest
{
    private DeletionPlan plan;

    private Session userSession;

    @BeforeEach
    void setup() throws RepositoryException
    {
        this.userSession = mock(Session.class);
        when(this.userSession.nodeExists(anyString())).thenReturn(true);
        this.plan = new DeletionPlan(DeletionOptions.archive(), "/content/x", this.userSession, null);
    }

    private Node referrer(final String path, final String type, final String name, final String title)
        throws RepositoryException
    {
        final Node node = mock(Node.class);
        when(node.getPath()).thenReturn(path);
        final NodeType nodeType = mock(NodeType.class);
        when(nodeType.getName()).thenReturn(type);
        when(node.getPrimaryNodeType()).thenReturn(nodeType);
        when(node.getName()).thenReturn(name);
        if (title != null) {
            final Property titleProperty = mock(Property.class);
            when(titleProperty.isMultiple()).thenReturn(false);
            when(titleProperty.getString()).thenReturn(title);
            when(node.hasProperty("title")).thenReturn(true);
            when(node.getProperty("title")).thenReturn(titleProperty);
        }
        this.plan.getBlockingReferrers().put(path, node);
        return node;
    }

    @Test
    void emptyPlanYieldsEmptyReport()
    {
        final ReferrerReport report = new ReferrerReport(this.plan);
        assertTrue(report.getGroups().isEmpty());
        assertEquals(0, report.getInaccessibleCount());
        assertEquals("", report.summary());
    }

    @Test
    void groupsByTypeWithTitlesAndNames() throws RepositoryException
    {
        this.referrer("/content/s1", "sub:Submission", "s1", "First");
        this.referrer("/content/s2", "sub:Submission", "s2", null);
        this.referrer("/content/v1", "wf:WorkflowVersion", "1.0", "1.0");
        final ReferrerReport report = new ReferrerReport(this.plan);
        assertEquals(2, report.getGroups().size());
        final ReferrerGroup submissions = report.getGroups().get(0);
        assertEquals("sub:Submission", submissions.getNodeType());
        assertEquals("submission", submissions.getLabel());
        assertEquals(List.of("First", "s2"), submissions.getNames());
        assertEquals("This item is referenced by 2 submissions (First, s2) and 1 workflow version (1.0).",
            report.summary());
    }

    @Test
    void multiValuedTitleFallsBackToTheName() throws RepositoryException
    {
        final Node node = this.referrer("/content/odd", "sub:Submission", "odd", "ignored");
        final Property titles = mock(Property.class);
        when(titles.isMultiple()).thenReturn(true);
        when(node.getProperty("title")).thenReturn(titles);
        assertEquals("This item is referenced by 1 submission (odd).", new ReferrerReport(this.plan).summary());
    }

    @Test
    void invisibleAndFailingReferrersAreOnlyCounted() throws RepositoryException
    {
        this.referrer("/content/visible", "sub:Submission", "ok", null);
        this.referrer("/content/hidden", "sub:Submission", "secret", null);
        when(this.userSession.nodeExists("/content/hidden")).thenReturn(false);
        final Node broken = this.referrer("/content/broken", "sub:Submission", "broken", null);
        when(broken.getPrimaryNodeType()).thenThrow(new RepositoryException("no type for you"));
        this.plan.getArchivedReferrers().add("/Archive/e/0/old");
        final ReferrerReport report = new ReferrerReport(this.plan);
        assertEquals(3, report.getInaccessibleCount());
        assertEquals("This item is referenced by 1 submission (ok) and 3 other items you cannot see.",
            report.summary());
    }

    @Test
    void singleInvisibleReferrerIsSingular()
    {
        this.plan.getArchivedReferrers().add("/Archive/e/0/old");
        assertEquals("This item is referenced by 1 other item you cannot see.",
            new ReferrerReport(this.plan).summary());
    }

    @Test
    void largeGroupsAreElided() throws RepositoryException
    {
        for (final int i : IntStream.range(0, 11).toArray()) {
            this.referrer("/content/s" + i, "sub:Submission", "s" + i, null);
        }
        final ReferrerReport report = new ReferrerReport(this.plan);
        assertEquals(10, report.getGroups().get(0).getNames().size());
        assertEquals(11, report.getGroups().get(0).getCount());
        assertTrue(report.summary().startsWith("This item is referenced by 11 submissions (s0,"));
        assertTrue(report.summary().contains(", …)."));
    }

    @Test
    void pluralsAreMostlyEnglish() throws RepositoryException
    {
        this.referrer("/content/p1", "x:Policy", "p1", null);
        this.referrer("/content/p2", "x:Policy", "p2", null);
        this.referrer("/content/d1", "x:WorkDay", "d1", null);
        this.referrer("/content/d2", "x:WorkDay", "d2", null);
        this.referrer("/content/b1", "x:Box", "b1", null);
        this.referrer("/content/b2", "x:Box", "b2", null);
        final String summary = new ReferrerReport(this.plan).summary();
        assertEquals("This item is referenced by 2 boxes (b1, b2), 2 policies (p1, p2)"
            + " and 2 work days (d1, d2).", summary);
    }
}
