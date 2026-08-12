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

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the corners of {@link ArchiveOperations} and {@link CascadeResolver} that a well-formed plan built by
 * the service itself cannot reach: defensive guards against plans that went stale between analysis and execution,
 * and content the links machinery does not recognize.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveOperationsTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/deletion.cnd", "SLING-INF/nodetypes/test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "iap:Archive");
        this.session.getRootNode().addNode("content");
        this.session.save();
    }

    private DeletionPlan plan(final DeletionOptions options, final String requestedPath)
    {
        return new DeletionPlan(options, requestedPath, this.session, this.context.resourceResolver());
    }

    @Test
    void unnoticedReferencesFailTheCommitCleanly() throws Exception
    {
        final Node victim = this.session.getNode("/content").addNode("victim");
        victim.addMixin("mix:referenceable");
        this.session.getNode("/content").addNode("holder").setProperty("ref", victim);
        this.session.save();
        // A stale plan that missed the referrer: the repository itself blocks the commit, and the failure is
        // reported as a clean exception instead of leaking the raw repository error
        final DeletionPlan plan = this.plan(DeletionOptions.of(false, true), "/content/victim");
        plan.getRoots().put("/content/victim", victim);
        final ArchiveOperations operations =
            ArchiveOperations.forResolver(this.context.resourceResolver());
        assertThrows(DeletionException.class, () -> operations.deletePermanently(plan));
    }

    @Test
    void checkedOutAncestorsDeletedByTheSameOperationAreSkipped() throws Exception
    {
        final Node versionable = this.session.getNode("/content").addNode("versionable");
        versionable.addMixin("mix:versionable");
        // A plain node holding a reference, not a recognized link: removing it needs its versionable ancestor
        // checked out, and goes through the raw removal path since the links machinery does not recognize it
        final Node stray = versionable.addNode("holder").addNode("stray");
        final Node victim = this.session.getNode("/content").addNode("victim");
        victim.addMixin("mix:referenceable");
        stray.setProperty("reference", victim);
        this.session.save();
        this.session.getWorkspace().getVersionManager().checkin("/content/versionable");

        final DeletionPlan plan = this.plan(DeletionOptions.of(true, true), "/content/versionable");
        plan.getLinksToRemove().put(stray.getPath(), stray);
        plan.getRoots().put("/content/versionable", versionable);
        plan.getRoots().put("/content/victim", victim);
        final ArchiveOperations operations =
            ArchiveOperations.forResolver(this.context.resourceResolver());
        operations.deletePermanently(plan);
        // The versionable ancestor was checked out for the link removal, then deleted itself; checking it back
        // in is correctly skipped instead of crashing
        assertFalse(this.session.nodeExists("/content/versionable"));
        assertFalse(this.session.nodeExists("/content/victim"));
    }

    @Test
    void resolverWithoutSessionIsRefused()
    {
        final ResourceResolver sessionless = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return null;
            }
        };
        assertThrows(DeletionException.class,
            () -> ArchiveOperations.forResolver(sessionless));
    }

    @Test
    void unresolvableLinkDefinitionsFallBackToLinkRemoval() throws Exception
    {
        final Node victim = this.session.getNode("/content").addNode("victim");
        victim.addMixin("mix:referenceable");
        final Node holder = this.session.getNode("/content").addNode("holder");
        final Node definition = this.session.getRootNode().addNode("definition", "iap:LinkDefinition");
        definition.setProperty("onDelete", "RECURSIVE_DELETE");
        final Node link = holder.addNode("iap:links", "iap:Links").addNode("link", "iap:Link");
        link.setProperty("type", definition);
        link.setProperty("reference", victim);
        this.session.save();

        // A resolver that cannot see the link resource, e.g. gone out of sync with the session: the policy
        // cannot be determined, so the resolver must fall back to the safe default of only removing the link
        final ResourceResolver blind = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return null;
            }
        };
        final DeletionPlan plan =
            new DeletionPlan(DeletionOptions.of(false, true), "/content/victim", this.session, blind);
        new CascadeResolver(plan, List.of()).resolve(victim);
        final String linkPath = link.getPath();
        assertEquals(List.of(linkPath), List.copyOf(plan.getLinksToRemove().keySet()));
        assertTrue(plan.getBlockingReferrers().isEmpty());
        // And the execution phase equally falls back to a direct removal for the unrecognizable link
        ArchiveOperations.forResolver(blind).deletePermanently(plan);
        assertFalse(this.session.nodeExists(linkPath));
        assertFalse(this.session.nodeExists("/content/victim"));
    }
}
