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

import javax.jcr.Node;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.spi.DeletionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link DeletionPlan}.
 *
 * @version $Id$
 */
class DeletionPlanTest
{
    private final DeletionPlan plan =
        new DeletionPlan(DeletionOptions.recoverable(), "/content/x", null, null);

    @Test
    void valuesAreKept()
    {
        assertSame(DeletionOptions.recoverable().isRecursive(), this.plan.getOptions().isRecursive());
        assertEquals("/content/x", this.plan.getRequestedPath());
        assertNull(this.plan.getUserSession());
        assertNull(this.plan.getServiceResolver());
        assertFalse(this.plan.isDenied());
        this.plan.deny();
        assertTrue(this.plan.isDenied());
        assertTrue(this.plan.getVetoes().isEmpty());
        assertTrue(this.plan.getArchivedReferrers().isEmpty());
    }

    @Test
    void modeFollowsTheOptions()
    {
        assertEquals(DeletionMode.ARCHIVE, this.plan.getMode());
        assertEquals(DeletionMode.PERMANENT,
            new DeletionPlan(DeletionOptions.of(false, true), "/x", null, null).getMode());
    }

    @Test
    void coverageIsAncestorBased()
    {
        assertFalse(this.plan.isCovered("/content/a"));
        this.plan.markRoot("/content/a", mock(Node.class));
        assertTrue(this.plan.isCovered("/content/a"));
        assertTrue(this.plan.isCovered("/content/a/b/c"));
        // A sibling sharing the path as a string prefix, but not as a path prefix
        assertFalse(this.plan.isCovered("/content/ab"));
        assertFalse(this.plan.isCovered("/content/Z"));
    }

    @Test
    void coverageSeesPastSiblingsSortingBelowASlash()
    {
        final Node node = mock(Node.class);
        this.plan.markRoot("/content/form", node);
        // A sibling whose name extends the first root's with a '-'. Since '-' (45) sorts below '/'
        // (47), "/content/form-2" falls *between* "/content/form" and "/content/form/child", so it,
        // and not the real ancestor, is the root closest to the path
        this.plan.markRoot("/content/form-2", node);
        assertTrue(this.plan.isCovered("/content/form/child"));
    }

    @Test
    void markingKeepsRootsMaximal()
    {
        final Node child = mock(Node.class);
        final Node parent = mock(Node.class);
        this.plan.markRoot("/content/p/c1", child);
        this.plan.markRoot("/content/p/c2", child);
        this.plan.markRoot("/content/other", child);
        this.plan.markRoot("/content/p", parent);
        assertEquals(2, this.plan.getRoots().size());
        assertSame(parent, this.plan.getRoots().get("/content/p"));
        assertTrue(this.plan.getRoots().containsKey("/content/other"));
    }

    @Test
    void visitReportsFirstTimeOnly()
    {
        assertTrue(this.plan.visit("id-1"));
        assertFalse(this.plan.visit("id-1"));
        assertTrue(this.plan.visit("id-2"));
    }

    @Test
    void normalizeDropsCoveredBookkeeping()
    {
        final Node node = mock(Node.class);
        this.plan.getLinksToRemove().put("/content/p/iap:links/l", node);
        this.plan.getLinksToRemove().put("/content/other/iap:links/l", node);
        this.plan.getBlockingReferrers().put("/content/p/sub", node);
        this.plan.getBlockingReferrers().put("/content/elsewhere", node);
        this.plan.markRoot("/content/p", node);
        this.plan.normalize();
        assertEquals(1, this.plan.getLinksToRemove().size());
        assertTrue(this.plan.getLinksToRemove().containsKey("/content/other/iap:links/l"));
        assertEquals(1, this.plan.getBlockingReferrers().size());
        assertTrue(this.plan.getBlockingReferrers().containsKey("/content/elsewhere"));
    }

    @Test
    void normalizeDropsCoveredBookkeepingPastSuchSiblings()
    {
        final Node node = mock(Node.class);
        this.plan.getBlockingReferrers().put("/content/form/sub", node);
        this.plan.getLinksToRemove().put("/content/form/iap:links/l", node);
        this.plan.markRoot("/content/form", node);
        this.plan.markRoot("/content/form-2", node);
        this.plan.normalize();
        assertTrue(this.plan.getBlockingReferrers().isEmpty());
        assertTrue(this.plan.getLinksToRemove().isEmpty());
    }
}
