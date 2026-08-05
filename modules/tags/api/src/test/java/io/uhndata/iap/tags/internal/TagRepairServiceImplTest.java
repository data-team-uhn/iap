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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TagRepairServiceImpl}. The repair itself writes one property per node and lets the propagation
 * editor do the recomputing, so what these check is which nodes get marked, which edge they are marked on, and what
 * happens when the repository will not cooperate.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagRepairServiceImplTest
{
    private static final String STATE = TagManager.COMPUTATION_STATE_PROPERTY;

    private final SlingContext context = new SlingContext();

    /** The query the service ran, captured so the tests can assert on it without a query engine. */
    private String executedQuery;

    /** What the fake query returns. */
    private final List<Resource> found = new ArrayList<>();

    /** Paths whose commit must fail, to exercise the batch-lost path. */
    private boolean commitFails;

    private int commits;

    private TagRepairServiceImpl service;

    @BeforeEach
    void setUp() throws Exception
    {
        this.service = new TagRepairServiceImpl();
        inject(factory(resolver()));
    }

    @Test
    void marksEveryNodeTheFailedQueryFinds() throws Exception
    {
        this.found.add(taggable("/content/a", null));
        this.found.add(taggable("/content/b", null));

        final RepairReport report = this.service.repairFailed();

        assertEquals(2, report.marked());
        assertTrue(report.isComplete());
        assertTrue(this.executedQuery.contains(STATE));
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/a"));
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/b"));
        assertEquals(1, this.commits);
    }

    /** The ordinary case for a node a processor failed on: `failed` becomes `recomputing`, which is a real change. */
    @Test
    void asksAFailedNodeToBeRecomputed() throws Exception
    {
        this.found.add(taggable("/content/broken", TagManager.STATE_FAILED));

        final RepairReport report = this.service.repairFailed();

        assertEquals(1, report.marked());
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/broken"));
    }

    /**
     * The anomaly: `recomputing` is written and consumed inside one commit, so finding it at rest means a commit was
     * interrupted in between. Writing it again would be no change and would strand the node for good, so the property
     * is cleared instead — which reaches the editor just as well, since it recomputes a node marked before the commit.
     */
    @Test
    void rescuesANodeStrandedByAnInterruptedCommit() throws Exception
    {
        this.found.add(taggable("/content/stranded", TagManager.STATE_RECOMPUTING));

        final RepairReport report = this.service.repairFailed();

        assertEquals(1, report.marked());
        assertNull(state("/content/stranded"));
    }

    @Test
    void repairsByTagNameAcrossAllFourProperties() throws Exception
    {
        this.found.add(taggable("/content/a", null));

        final RepairReport report = this.service.repair("retired");

        assertEquals(1, report.marked());
        assertTrue(this.executedQuery.contains("[tags] = 'retired'"));
        assertTrue(this.executedQuery.contains("[computedTags] = 'retired'"));
        assertTrue(this.executedQuery.contains("[inheritedTags] = 'retired'"));
        assertTrue(this.executedQuery.contains("[aggregatedTags] = 'retired'"));
    }

    /** A name that is not a tag name is refused rather than quoted into the query. */
    @Test
    void refusesATagNameThatCouldNotBeOne() throws Exception
    {
        this.found.add(taggable("/content/a", null));

        final RepairReport report = this.service.repair("' or [tags] is not null --");

        assertEquals(0, report.marked());
        assertTrue(report.isComplete());
        assertEquals(null, this.executedQuery);
        assertNull(state("/content/a"));
    }

    @Test
    void countsAndSkipsNodesItCannotWrite() throws Exception
    {
        this.found.add(new ReadOnlyResource(taggable("/content/locked", null)));
        this.found.add(taggable("/content/writable", null));

        final RepairReport report = this.service.repairFailed();

        assertEquals(1, report.marked());
        assertEquals(1, report.failed());
        assertFalse(report.isComplete());
        // The unwritable one did not stop the rest
        assertEquals(TagManager.STATE_RECOMPUTING, state("/content/writable"));
    }

    @Test
    void reportsABatchTheRepositoryRefuses() throws Exception
    {
        this.found.add(taggable("/content/a", null));
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
            this.found.add(taggable("/content/n" + i, null));
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

    @Test
    void reportsNothingWhenTheServiceUserIsMissing() throws Exception
    {
        inject(new TestResolverFactory(null));

        final RepairReport report = this.service.repairFailed();

        assertEquals(0, report.marked());
        assertEquals(0, report.failed());
    }

    /** Injects the OSGi reference the way the rest of the repo's tests do: DS metadata does not exist here. */
    private void inject(final ResourceResolverFactory factory) throws Exception
    {
        final Field reference = TagRepairServiceImpl.class.getDeclaredField("resolverFactory");
        reference.setAccessible(true);
        reference.set(this.service, factory);
    }

    private Resource taggable(final String path, final String state) throws Exception
    {
        final Map<String, Object> properties = new HashMap<>();
        if (state != null) {
            properties.put(STATE, state);
        }
        return this.context.create().resource(path, properties);
    }

    private String state(final String path)
    {
        return this.context.resourceResolver().getResource(path).getValueMap().get(STATE, String.class);
    }

    /** A resolver whose query returns whatever the test staged, and whose commit fails on demand. */
    private ResourceResolver resolver()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                TagRepairServiceImplTest.this.executedQuery = query;
                return TagRepairServiceImplTest.this.found.iterator();
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

            @Override
            public void revert()
            {
                // MockSession.refresh throws, and rollback is the repository's job to prove, not this test's
            }
        };
    }

    private ResourceResolverFactory factory(final ResourceResolver resolver)
    {
        return new TestResolverFactory(resolver);
    }

    /** Stands in for a node the repair may read but not write, e.g. one its service user has no rights on. */
    private static final class ReadOnlyResource extends org.apache.sling.api.resource.ResourceWrapper
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
