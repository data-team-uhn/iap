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

import static io.uhndata.iap.deletion.api.DeletionOptions.REFUSE_IF_REFERENCED;
import static io.uhndata.iap.deletion.api.DeletionOptions.DELETE_PERMANENTLY;
import static io.uhndata.iap.deletion.api.DeletionOptions.DELETE_RECURSIVELY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for cases a plan made by the service does not reach: a plan that went stale before it ran, and links the
 * links module does not know. They drive {@link ArchiveOperations} and {@link CascadeResolver} directly.
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
        this.session.getRootNode().addNode("Archive", "del:Archive");
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
        // A plan that missed the referrer: Oak refuses the commit, and the failure is a DeletionException
        final DeletionPlan plan = this.plan(DeletionOptions.of(REFUSE_IF_REFERENCED, DELETE_PERMANENTLY),
            "/content/victim");
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
        // A plain node holding a reference, which the links module does not know: it is removed directly,
        // after its versionable ancestor is checked out
        final Node stray = versionable.addNode("holder").addNode("stray");
        final Node victim = this.session.getNode("/content").addNode("victim");
        victim.addMixin("mix:referenceable");
        stray.setProperty("reference", victim);
        this.session.save();
        this.session.getWorkspace().getVersionManager().checkin("/content/versionable");

        final DeletionPlan plan = this.plan(DeletionOptions.of(DELETE_RECURSIVELY, DELETE_PERMANENTLY),
            "/content/versionable");
        plan.getLinksToRemove().put(stray.getPath(), stray);
        plan.getRoots().put("/content/versionable", versionable);
        plan.getRoots().put("/content/victim", victim);
        final ArchiveOperations operations =
            ArchiveOperations.forResolver(this.context.resourceResolver());
        operations.deletePermanently(plan);
        // The ancestor checked out for the link removal was deleted too, so there is nothing to check back in
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
        final Node definition = this.session.getRootNode().addNode("definition", "link:InternalDefinition");
        definition.setProperty("onDelete", "RECURSIVE_DELETE");
        final Node link = holder.addNode("link:links", "link:Links").addNode("link", "link:Link");
        link.setProperty("type", definition);
        link.setProperty("reference", victim);
        this.session.save();

        // A resolver that cannot see the link: its policy is unknown, so only the link is removed
        final ResourceResolver blind = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return null;
            }
        };
        final DeletionPlan plan =
            new DeletionPlan(DeletionOptions.of(REFUSE_IF_REFERENCED, DELETE_PERMANENTLY), "/content/victim",
                this.session, blind);
        new CascadeResolver(plan, List.of()).resolve(victim);
        final String linkPath = link.getPath();
        assertEquals(List.of(linkPath), List.copyOf(plan.getLinksToRemove().keySet()));
        assertTrue(plan.getBlockingReferrers().isEmpty());
        // The link it cannot read is removed directly
        ArchiveOperations.forResolver(blind).deletePermanently(plan);
        assertFalse(this.session.nodeExists(linkPath));
        assertFalse(this.session.nodeExists("/content/victim"));
    }
}
