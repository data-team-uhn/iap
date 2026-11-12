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
package io.uhndata.iap.tags.internal;

import java.util.HashSet;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.api.TagRepairException;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TagRepairServiceImpl}: which nodes get marked, with what value, and what happens when a node or a
 * save is refused, or the repair cannot run at all. The queries run against a real repository, so a tag name is
 * matched as the repository matches it. The recomputation is the propagation editor's, tested on its own.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagRepairServiceImplTest
{
    private static final String STATE = TagManager.COMPUTATION_STATE_PROPERTY;

    private static final String RETIRED = "retired";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    /** Paths the service user may read but not write. */
    private final Set<String> readOnly = new HashSet<>();

    /** Paths removed between the query finding them and the repair marking them. */
    private final Set<String> removedSinceFound = new HashSet<>();

    private boolean commitFails;

    private int commits;

    private TagRepairServiceImpl service;

    /** How many services this test has activated, so that each one's factory outranks the last. */
    private int activations;

    @BeforeEach
    void setUp()
    {
        this.service = activateServiceServedBy(serviceUserResolver());
    }

    @Test
    void marksEveryNodeLeftStaleAndNothingElse() throws Exception
    {
        stale("/content/a", TagManager.STATE_FAILED);
        stale("/content/b", TagManager.STATE_FAILED);
        tagged("/content/healthy", TagManager.TAGS_PROPERTY, RETIRED);

        final RepairReport report = this.service.repairFailed();

        assertEquals(2, report.marked());
        assertTrue(report.isComplete());
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/a"));
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/b"));
        assertNull(state("/content/healthy"));
        assertEquals(1, this.commits);
    }

    /** A node found at {@code recomputing} has the mark removed, since writing the same value changes nothing. */
    @Test
    void rescuesANodeStrandedByAnInterruptedCommit() throws Exception
    {
        stale("/content/stranded", TagManager.STATE_RECOMPUTING);

        final RepairReport report = this.service.repairFailed();

        assertEquals(1, report.marked());
        assertNull(state("/content/stranded"));
    }

    @Test
    void repairsByTagNameInEachOfTheFourProperties() throws Exception
    {
        tagged("/content/placed", TagManager.TAGS_PROPERTY, RETIRED);
        for (final Phase phase : Phase.values()) {
            tagged("/content/" + phase.name(), phase.getPropertyName(), RETIRED);
        }
        tagged("/content/other", TagManager.TAGS_PROPERTY, "kept");

        final RepairReport report = this.service.repair(RETIRED);

        assertEquals(4, report.marked());
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/placed"));
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/" + Phase.BOTTOM_UP.name()));
        assertNull(state("/content/other"));
    }

    /** A tag's name is whatever its definition says, and a definition may well name one with a space. */
    @Test
    void repairsATagWhoseNameHasASpace() throws Exception
    {
        tagged("/content/survey", TagManager.TAGS_PROPERTY, "PATIENT SURVEY");

        final RepairReport report = this.service.repair("PATIENT SURVEY");

        assertEquals(1, report.marked());
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/survey"));
    }

    /** The name is bound to the query, never pasted into it, so a name shaped like query text is only a name. */
    @Test
    void matchesANameShapedLikeQueryTextLiterally() throws Exception
    {
        tagged("/content/a", TagManager.TAGS_PROPERTY, RETIRED);

        final RepairReport report = this.service.repair("' OR [tags] IS NOT NULL OR [tags] = '");

        assertEquals(0, report.marked());
        assertNull(state("/content/a"));
    }

    @Test
    void countsAndSkipsNodesItCannotWrite() throws Exception
    {
        stale("/content/locked", TagManager.STATE_FAILED);
        stale("/content/writable", TagManager.STATE_FAILED);
        this.readOnly.add("/content/locked");

        final RepairReport report = this.service.repairFailed();

        assertEquals(1, report.marked());
        assertEquals(1, report.failed());
        assertFalse(report.isComplete());
        // The unwritable one did not stop the rest
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/writable"));
    }

    /** A node gone by the time it is marked has nothing left to repair, which is not a failure. */
    @Test
    void skipsANodeRemovedSinceTheQueryFoundIt() throws Exception
    {
        stale("/content/gone", TagManager.STATE_FAILED);
        this.removedSinceFound.add("/content/gone");

        final RepairReport report = this.service.repairFailed();

        assertEquals(0, report.marked());
        assertTrue(report.isComplete());
    }

    @Test
    void reportsABatchTheRepositoryRefuses() throws Exception
    {
        stale("/content/a", TagManager.STATE_FAILED);
        this.commitFails = true;

        final RepairReport report = this.service.repairFailed();

        assertEquals(0, report.marked());
        assertEquals(1, report.failed());
        assertFalse(report.isComplete());
    }

    @Test
    void savesInBatchesRatherThanOnceAtTheEnd() throws Exception
    {
        for (int i = 0; i < TagRepairServiceImpl.BATCH_SIZE + 1; i++) {
            stale("/content/n" + i, TagManager.STATE_FAILED);
        }

        final RepairReport report = this.service.repairFailed();

        assertEquals(TagRepairServiceImpl.BATCH_SIZE + 1, report.marked());
        // One full batch, then the remainder
        assertEquals(2, this.commits);
    }

    @Test
    void writesNothingWhenThereIsNothingToRepair() throws Exception
    {
        final RepairReport report = this.service.repairFailed();

        assertEquals(0, report.marked());
        assertTrue(report.isComplete());
        assertEquals(0, this.commits);
    }

    /** Without its service user the repair cannot look, and says so rather than report that nothing needed it. */
    @Test
    void refusesWhenTheServiceUserIsMissing() throws Exception
    {
        tagged("/content/a", TagManager.TAGS_PROPERTY, RETIRED);
        final TagRepairServiceImpl withoutServiceUser = activateServiceServedBy(null);

        assertThrows(TagRepairException.class, () -> withoutServiceUser.repair(RETIRED));
        assertNull(state("/content/a"));
    }

    @Test
    void refusesWhenTheQueryCannotRun() throws Exception
    {
        final TagRepairServiceImpl withoutQueries = activateServiceServedBy(resolverWithoutQueries());

        assertThrows(TagRepairException.class, withoutQueries::repairFailed);
    }

    /**
     * Activates a repair service whose service user logs in as {@link TestResolverFactory} describes. Each activation
     * registers its factory above every earlier one, the mock repository's own included, so the new service binds it.
     *
     * @param resolver the resolver the service user is served, or {@code null} for a missing service user
     * @return the activated service
     */
    private TagRepairServiceImpl activateServiceServedBy(final ResourceResolver resolver)
    {
        this.context.registerService(ResourceResolverFactory.class, new TestResolverFactory(resolver),
            Constants.SERVICE_RANKING, ++this.activations);
        return this.context.registerInjectActivateService(new TagRepairServiceImpl());
    }

    /** A node a failed or interrupted commit left with a mark. */
    private void stale(final String path, final String state) throws RepositoryException
    {
        newContentNode(path).setProperty(STATE, state);
        session().save();
    }

    /** A node carrying a tag in one of the properties a tag name is stored in. */
    private void tagged(final String path, final String property, final String tag) throws RepositoryException
    {
        newContentNode(path).setProperty(property, new String[] { tag });
        session().save();
    }

    private Node newContentNode(final String path) throws RepositoryException
    {
        final Node content = session().nodeExists("/content")
            ? session().getNode("/content")
            : session().getRootNode().addNode("content", "nt:unstructured");
        return content.addNode(path.substring(path.lastIndexOf('/') + 1), "nt:unstructured");
    }

    private Session session()
    {
        return this.context.resourceResolver().adaptTo(Session.class);
    }

    private String state(final String path)
    {
        this.context.resourceResolver().refresh();
        return this.context.resourceResolver().getResource(path).getValueMap().get(STATE, String.class);
    }

    /** The test's own resolver, with read-only and vanished nodes on demand, and a commit that fails on demand. */
    private ResourceResolver serviceUserResolver()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                if (TagRepairServiceImplTest.this.removedSinceFound.contains(path)) {
                    return null;
                }
                final Resource resource = super.getResource(path);
                return TagRepairServiceImplTest.this.readOnly.contains(path) ? new ReadOnlyResource(resource)
                    : resource;
            }

            @Override
            public void commit() throws PersistenceException
            {
                TagRepairServiceImplTest.this.commits++;
                if (TagRepairServiceImplTest.this.commitFails) {
                    throw new PersistenceException("refused");
                }
                super.commit();
            }
        };
    }

    /** A resolver whose repository refuses to run any query. */
    private ResourceResolver resolverWithoutQueries() throws RepositoryException
    {
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(workspace.getQueryManager()).thenThrow(new RepositoryException("no queries"));
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? (T) session : super.adaptTo(type);
            }
        };
    }

    /** Stands in for a node the repair may read but not write, e.g. one its service user has no rights on. */
    private static final class ReadOnlyResource extends ResourceWrapper
    {
        ReadOnlyResource(final Resource resource)
        {
            super(resource);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            return type == ModifiableValueMap.class ? null : super.adaptTo(type);
        }
    }
}
