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
package io.uhndata.iap.search.index.internal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit tests for {@link EntityIndexer}.
 *
 * <p>
 * The indexer is never registered as a service, so the only changes it hears of are those a test delivers. Stopping
 * it waits for its background work and closes its index, which is when a test reads what was indexed. Its reference
 * to the resolver factory is set by reflection, as SCR would.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityIndexerTest
{
    private static final String HOMEPAGE = "/Requests";

    private static final String BUCKET = HOMEPAGE + "/ab";

    private static final String FIRST = BUCKET + "/first";

    private static final String ANSWER = "test:Answer";

    private static final Query ENTITIES = new TermQuery(new Term(IndexFields.KIND_FIELD, IndexFields.ENTITY_KIND));

    private static final Query DESCENDANTS =
        new TermQuery(new Term(IndexFields.KIND_FIELD, IndexFields.DESCENDANT_KIND));

    private final SlingContext context = TestRepository.newContext();

    @TempDir
    private Path directory;

    private Session session;

    private ResourceResolverFactory resolvers;

    private String[] descendantTypes = { ANSWER, "test:Review" };

    private String indexDirectory;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = TestRepository.session(this.context);
        request(FIRST).addNode("days", ANSWER).setProperty("value", 3L);
        this.session.save();
        this.resolvers = this.context.getService(ResourceResolverFactory.class);
        this.indexDirectory = this.directory.toString();
    }

    @Test
    void indexesWhatIsAlreadyThereWhenFirstStarted() throws Exception
    {
        stop(start());

        assertEquals(List.of(FIRST + "/days", FIRST), indexedPaths());
    }

    @Test
    void keepsAnIndexItFindsComplete() throws Exception
    {
        stop(start());
        request(BUCKET + "/unheard");
        this.session.save();

        stop(start());

        assertEquals(1, indexed(ENTITIES));
    }

    @Test
    void rebuildsAnIndexWrittenForOtherDescendants() throws Exception
    {
        stop(start());
        request(FIRST).addNode("aside", "test:Note");
        this.session.save();
        this.descendantTypes = new String[] { ANSWER, "test:Note" };

        stop(start());

        assertEquals(2, indexed(DESCENDANTS));
    }

    @Test
    void reindexesTheEntityAChangeIsPartOf() throws Exception
    {
        final EntityIndexer indexer = ready();
        this.session.getNode(FIRST + "/days").setProperty("value", 5L);
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.CHANGED, FIRST + "/days")));
        stop(indexer);

        assertEquals(List.of(FIRST + "/days", FIRST), indexedPaths());
        assertEquals(1, indexed(LongPoint.newExactQuery("value|Long|number", 5)));
    }

    @Test
    void reindexesAnEntityWhoseDescendantIsGone() throws Exception
    {
        final EntityIndexer indexer = ready();
        this.session.getNode(FIRST + "/days").remove();
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.REMOVED, FIRST + "/days")));
        stop(indexer);

        assertEquals(List.of(FIRST), indexedPaths());
    }

    @Test
    void unindexesAnEntityThatIsGone() throws Exception
    {
        final EntityIndexer indexer = ready();
        this.session.getNode(FIRST).remove();
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.REMOVED, FIRST)));
        stop(indexer);

        assertEquals(List.of(), indexedPaths());
    }

    @Test
    void indexesTheEntitiesABucketBringsIn() throws Exception
    {
        final EntityIndexer indexer = ready();
        request(HOMEPAGE + "/xy/zz/moved");
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.ADDED, HOMEPAGE + "/xy")));
        stop(indexer);

        assertEquals(List.of(FIRST + "/days", FIRST, HOMEPAGE + "/xy/zz/moved"), indexedPaths());
    }

    @Test
    void unindexesTheEntitiesOfABucketThatIsGone() throws Exception
    {
        final EntityIndexer indexer = ready();
        this.session.getNode(BUCKET).remove();
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.REMOVED, BUCKET)));
        stop(indexer);

        assertEquals(List.of(), indexedPaths());
    }

    @Test
    void ignoresAChangeToABucket() throws Exception
    {
        final EntityIndexer indexer = ready();
        request(BUCKET + "/unheard");
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.CHANGED, BUCKET)));
        stop(indexer);

        assertEquals(1, indexed(ENTITIES));
    }

    @Test
    void keepsAnEntityABatchRemovesAndPutsBack() throws Exception
    {
        final EntityIndexer indexer = ready();

        // The bucket moved away and back: the entity is where it was, after a removal of everything below it
        indexer.onChange(List.of(change(ChangeType.CHANGED, FIRST + "/days"), change(ChangeType.REMOVED, BUCKET),
            change(ChangeType.ADDED, BUCKET)));
        stop(indexer);

        assertEquals(List.of(FIRST + "/days", FIRST), indexedPaths());
    }

    @Test
    void rebuildsWhenAChangeCannotBeRead() throws Exception
    {
        final ResourceResolverFactory flaky = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(flaky.getServiceResourceResolver(any()))
            .thenAnswer(invocation -> this.resolvers.getServiceResourceResolver(invocation.getArgument(0)))
            .thenThrow(new LoginException("Not mapped yet"))
            .thenAnswer(invocation -> this.resolvers.getServiceResourceResolver(invocation.getArgument(0)));
        stop(start(flaky));
        final EntityIndexer indexer = start(flaky);
        request(BUCKET + "/unheard");
        this.session.save();

        indexer.onChange(List.of(change(ChangeType.CHANGED, FIRST)));
        stop(indexer);

        assertEquals(2, indexed(ENTITIES));
    }

    @Test
    void leavesAFailedRebuildToBeRedone() throws Exception
    {
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(any())).thenThrow(new LoginException("Not mapped yet"));
        stop(start(refusing));

        stop(start());

        assertEquals(1, indexed(ENTITIES));
    }

    @Test
    void makesWhatItIndexedDurableWhileRunning() throws Exception
    {
        final EntityIndexer indexer = ready();
        this.session.getNode(FIRST).remove();
        this.session.save();
        indexer.onChange(List.of(change(ChangeType.REMOVED, FIRST)));

        indexer.commit();

        assertEquals(0, indexed(ENTITIES));
        stop(indexer);
    }

    @Test
    void leavesARebuildThatCannotReadToBeRedone() throws Exception
    {
        final Session unreadable = Mockito.mock(Session.class);
        Mockito.when(unreadable.nodeExists(any())).thenThrow(new RepositoryException("Unreadable"));
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(unreadable);
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(any())).thenReturn(resolver);
        final EntityIndexer failing = start(broken);
        stop(failing);

        stop(start());

        assertEquals(1, indexed(ENTITIES));
    }

    @Test
    void vouchesForItsIndexOnlyOnceStopped() throws Exception
    {
        final EntityIndexer indexer = ready();
        assertTrue(indexer.isComplete());
        assertEquals(Map.of(), lastCommitData());

        stop(indexer);

        assertEquals(Set.of("layout"), lastCommitData().keySet());
    }

    @Test
    void rebuildsWhatAFailedWriteClosed() throws Exception
    {
        final EntityIndexer indexer = ready();
        request(BUCKET + "/unheard");
        this.session.save();
        indexer.onChange(List.of(change(ChangeType.CHANGED, FIRST)));
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            indexer.commit();
        } finally {
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        indexer.onChange(List.of(change(ChangeType.CHANGED, FIRST)));
        stop(indexer);

        stop(start());

        assertEquals(2, indexed(ENTITIES));
    }

    @Test
    void keepsItsIndexUnderSlingHomeUnlessToldWhere() throws Exception
    {
        this.indexDirectory = "";
        final String slingHome = System.setProperty("sling.home", this.directory.toString());
        try {
            stop(start());
        } finally {
            restore("sling.home", slingHome);
        }

        assertTrue(Arrays.stream(this.directory.resolve("entity-index/Requests").toFile().list())
            .anyMatch(name -> name.startsWith("segments_")));
    }

    /**
     * An indexer whose index is complete, so that what it writes next is only what a test delivers.
     *
     * @return the started indexer
     */
    private EntityIndexer ready() throws Exception
    {
        stop(start());
        return start();
    }

    private EntityIndexer start() throws Exception
    {
        return start(this.resolvers);
    }

    private EntityIndexer start(final ResourceResolverFactory factory) throws Exception
    {
        final EntityIndexer indexer = new EntityIndexer();
        final Field reference = EntityIndexer.class.getDeclaredField("resolverFactory");
        reference.setAccessible(true);
        reference.set(indexer, factory);
        indexer.activate(config(), this.context.bundleContext());
        return indexer;
    }

    private static void stop(final EntityIndexer indexer) throws Exception
    {
        indexer.deactivate();
    }

    private EntityIndexer.Config config()
    {
        final EntityIndexer.Config config = Mockito.mock(EntityIndexer.Config.class);
        Mockito.when(config.resource_paths()).thenReturn(HOMEPAGE);
        Mockito.when(config.entity_type()).thenReturn("test:Request");
        Mockito.when(config.descendant_types()).thenReturn(this.descendantTypes);
        Mockito.when(config.index_directory()).thenReturn(this.indexDirectory);
        Mockito.when(config.commit_seconds()).thenReturn(30L);
        return config;
    }

    private Node request(final String path) throws RepositoryException
    {
        Node node = this.session.getRootNode();
        final String[] names = path.substring(1).split("/");
        for (int i = 0; i < names.length - 1; ++i) {
            node = node.hasNode(names[i]) ? node.getNode(names[i]) : node.addNode(names[i]);
        }
        final String name = names[names.length - 1];
        return node.hasNode(name) ? node.getNode(name) : node.addNode(name, "test:Request");
    }

    private int indexed(final Query query) throws IOException
    {
        try (SearchedIndex index = SearchedIndex.at(this.directory)) {
            return index.count(query);
        }
    }

    private Map<String, String> lastCommitData() throws IOException
    {
        try (FSDirectory index = FSDirectory.open(this.directory)) {
            final List<IndexCommit> commits = DirectoryReader.listCommits(index);
            return commits.get(commits.size() - 1).getUserData();
        }
    }

    private List<String> indexedPaths() throws IOException
    {
        try (SearchedIndex index = SearchedIndex.at(this.directory)) {
            return index.paths(Sort.INDEXORDER);
        }
    }

    private static ResourceChange change(final ChangeType type, final String path)
    {
        return new ResourceChange(type, path, false);
    }

    private static void restore(final String property, final String value)
    {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
