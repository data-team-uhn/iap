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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.store.FSDirectory;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.spi.EntityListingBackend;

/**
 * Keeps one index of the entities under a homepage in step with the repository, and answers the listings of them it
 * can answer exactly. Every change below the homepage reindexes the entity it is part of, whole; a removed entity is
 * unindexed. An index that was not closed cleanly, or was written in another layout, is rebuilt on activation, and
 * serves no listing until it is complete.
 *
 * <p>
 * Each configuration is one index, so that another kind of entity is indexed by configuring it rather than by code.
 * The index holds everything the indexing service user reads, which is everything under the homepage.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { EntityListingBackend.class, ResourceChangeListener.class,
    ExternalResourceChangeListener.class },
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    property = { ResourceChangeListener.CHANGES + "=ADDED", ResourceChangeListener.CHANGES + "=CHANGED",
        ResourceChangeListener.CHANGES + "=REMOVED" })
@Designate(ocd = EntityIndexer.Config.class, factory = true)
public class EntityIndexer implements EntityListingBackend, ResourceChangeListener, ExternalResourceChangeListener
{
    /**
     * What an administrator configures, one configuration per index.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @ObjectClassDefinition(name = "IAP Entity Index",
        description = "A search index of the entities under one homepage, used to list them without a join per"
            + " condition on their descendants.")
    public @interface Config
    {
        /**
         * The homepage, named as Sling's observation expects it so that the changes followed are those below it.
         *
         * @return an absolute path
         */
        @AttributeDefinition(name = "Homepage", description = "The path the indexed entities are filed under.")
        String resource_paths();

        /**
         * The node type of the entities indexed.
         *
         * @return a node type name
         */
        @AttributeDefinition(name = "Entity type", description = "The node type of the entities indexed, such as"
            + " sub:Submission.")
        String entity_type();

        /**
         * The node types of the descendants indexed with their entity.
         *
         * @return node type names
         */
        @AttributeDefinition(name = "Descendant types", description = "The node types of the descendants a listing"
            + " can filter on, such as sub:Answer. Other descendants are not indexed.")
        String[] descendant_types();

        /**
         * Where the index is kept.
         *
         * @return a directory, or empty for one under {@code sling.home} named after the homepage
         */
        @AttributeDefinition(name = "Index directory", description = "Where the index is kept. Empty keeps it in"
            + " sling.home, under entity-index, named after the homepage.")
        String index_directory() default "";

        /**
         * How long changes may wait before they are made durable.
         *
         * @return a number of seconds
         */
        @AttributeDefinition(name = "Commit interval", description = "How many seconds may pass before indexed"
            + " changes are written to disk. After a crash, the whole index is rebuilt.")
        long commit_seconds() default 30;
    }

    /** The subservice the indexing service user is mapped to. */
    static final String INDEXER_SUBSERVICE = "indexer";

    /** Changed whenever documents are laid out differently, so that an index written the old way is rebuilt. */
    private static final String LAYOUT_VERSION = "1";

    /** What the indexer was doing when a rebuild failed, as error tracking records it. */
    private static final String REBUILDING_ACTIVITY = "rebuildIndex";

    /** How long to wait before trying a rebuild again, when the indexing service user could not log in. */
    private static final long RETRY_SECONDS = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityIndexer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private String homepage;

    private String entityType;

    private String layout;

    private EntityDocuments documents;

    private EntityIndex index;

    private ListingSearch listings;

    private ScheduledExecutorService maintenance;

    /**
     * Whether the index was last rebuilt, or found sealed, so that it matches the repository but for changes it is
     * about to hear of. It stops matching if a failed write closes it, which {@link #isComplete()} accounts for.
     */
    private volatile boolean complete;

    /**
     * Opens the index and starts making its changes durable. An index that was not sealed in the current layout is
     * rebuilt in the background.
     *
     * @param config the index's configuration
     * @param context the bundle's context, which says where {@code sling.home} is
     * @throws IOException if the index cannot be opened
     */
    @Activate
    public void activate(final Config config, final BundleContext context) throws IOException
    {
        this.homepage = config.resource_paths();
        this.entityType = config.entity_type();
        final Set<String> descendantTypes = Set.of(config.descendant_types());
        this.documents = new EntityDocuments(descendantTypes);
        this.layout = String.join(";", LAYOUT_VERSION, this.entityType, String.join(",",
            descendantTypes.stream().sorted().toList()));
        this.index = new EntityIndex(FSDirectory.open(directory(config, context)));
        this.listings = new ListingSearch(this.index, this.homepage, this.entityType, descendantTypes);
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        // A rebuild waiting to be retried is not worth delaying a shutdown for
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.maintenance = executor;
        this.maintenance.scheduleWithFixedDelay(this::commit, config.commit_seconds(), config.commit_seconds(),
            TimeUnit.SECONDS);
        if (this.index.isSealed(this.layout)) {
            this.complete = true;
            // Unsealed at once: from now on, only a clean stop vouches for it
            this.index.commit();
        } else {
            rebuildLater(0);
        }
    }

    /**
     * Stops the maintenance and closes the index, sealed if it is complete.
     *
     * @throws IOException if the index cannot be written
     * @throws InterruptedException if interrupted while waiting for the maintenance to finish
     */
    @Deactivate
    public void deactivate() throws IOException, InterruptedException
    {
        this.maintenance.shutdown();
        this.maintenance.awaitTermination(1, TimeUnit.MINUTES);
        if (isComplete()) {
            this.index.seal(this.layout);
        }
        this.index.close();
    }

    @Override
    public boolean canServe(final EntityListing listing)
    {
        return isComplete() && this.listings.canServe(listing);
    }

    @Override
    public Iterator<String> find(final EntityListing listing, final ResourceResolver resolver) throws IOException
    {
        return this.listings.find(listing, resolver);
    }

    @Override
    public void onChange(final List<ResourceChange> changes)
    {
        if (!this.index.isOpen()) {
            // Closed by a failed write, which was reported then; it is rebuilt at the next start
            return;
        }
        try (ResourceResolver resolver = serviceResolver()) {
            final EntityLocator locator = locator(resolver);
            final Set<String> stale = new LinkedHashSet<>();
            for (final ResourceChange change : changes) {
                stale.addAll(staleEntities(change, locator));
            }
            reindex(stale, locator);
        } catch (final LoginException | RepositoryException | IOException e) {
            // What was missed is unknown, so all of it is redone
            LOGGER.warn("Failed to index changes below {}, rebuilding: {}", this.homepage, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(EntityIndexer.class, "indexChanges").about(this.homepage));
            rebuildLater(0);
        }
    }

    /**
     * Indexes every entity anew. The index is not complete until this succeeds; if the indexing service user cannot
     * log in yet, as happens while the instance starts, it is tried again a little later.
     */
    void rebuild()
    {
        this.complete = false;
        try (ResourceResolver resolver = serviceResolver()) {
            final EntityLocator locator = locator(resolver);
            this.index.clear();
            final List<String> entities = locator.under(this.homepage);
            for (final String entity : entities) {
                index(locator, entity);
            }
            this.index.commit();
            this.complete = true;
            LOGGER.info("Indexed the {} entities below {}", entities.size(), this.homepage);
        } catch (final LoginException e) {
            LOGGER.warn("Cannot index the entities below {} yet, retrying: {}", this.homepage, e.getMessage());
            ErrorLogger.logError(e, ErrorContext.of(EntityIndexer.class, REBUILDING_ACTIVITY).about(this.homepage));
            rebuildLater(RETRY_SECONDS);
        } catch (final RepositoryException | IOException e) {
            LOGGER.error("Failed to index the entities below {}: {}", this.homepage, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(EntityIndexer.class, REBUILDING_ACTIVITY).about(this.homepage));
        }
    }

    /**
     * Makes what was indexed so far durable. A failure may close the index, which then stays incomplete until the
     * next start rebuilds it.
     */
    void commit()
    {
        try {
            this.index.commit();
        } catch (final IOException e) {
            LOGGER.error("Failed to write the index of {}: {}", this.homepage, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(EntityIndexer.class, "commitIndex").about(this.homepage));
        }
    }

    /**
     * Whether the index matches the repository, but for changes it is about to hear of.
     *
     * @return {@code true} once the index is rebuilt, or was found sealed, and while it can still be written
     */
    boolean isComplete()
    {
        return this.complete && this.index.isOpen();
    }

    private void rebuildLater(final long delaySeconds)
    {
        if (!this.maintenance.isShutdown()) {
            this.maintenance.schedule(this::rebuild, delaySeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * The entities a change leaves indexed out of date: the one it is part of, or, for a change above any entity,
     * those it added or removed.
     *
     * @param change a change below the homepage
     * @param locator finds the entities
     * @return the paths of the entities to reindex, and of those to unindex, which no longer exist
     * @throws RepositoryException if the repository cannot be read
     */
    private Set<String> staleEntities(final ResourceChange change, final EntityLocator locator)
        throws RepositoryException
    {
        final Optional<Node> entity = locator.enclosing(change.getPath());
        if (entity.isPresent()) {
            return Set.of(entity.get().getPath());
        }
        return switch (change.getType()) {
            // Such as a bucket of the prefix tree, with whatever was moved in with it
            case ADDED -> Set.copyOf(locator.under(change.getPath()));
            case REMOVED -> Set.of(change.getPath());
            default -> Set.of();
        };
    }

    /**
     * Brings the index up to date with some entities, as the repository now stands.
     *
     * @param stale the paths of the entities indexed out of date
     * @param locator finds the entities
     * @throws RepositoryException if the repository cannot be read
     * @throws IOException if the index cannot be written
     */
    private void reindex(final Set<String> stale, final EntityLocator locator) throws RepositoryException, IOException
    {
        // Removals first: one may cover a path a later change in the batch put an entity back under
        final List<String> current = new ArrayList<>();
        for (final String path : stale) {
            if (locator.isEntity(path)) {
                current.add(path);
            } else {
                this.index.remove(path);
            }
        }
        for (final String entity : current) {
            index(locator, entity);
        }
    }

    private void index(final EntityLocator locator, final String entity) throws RepositoryException, IOException
    {
        this.index.replace(entity, this.documents.of(locator.get(entity)));
    }

    private EntityLocator locator(final ResourceResolver resolver)
    {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "The indexing service user reads through a JCR session");
        return new EntityLocator(session, this.homepage, this.entityType);
    }

    private ResourceResolver serviceResolver() throws LoginException
    {
        return this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, INDEXER_SUBSERVICE));
    }

    private Path directory(final Config config, final BundleContext context)
    {
        if (StringUtils.isNotBlank(config.index_directory())) {
            return Path.of(config.index_directory());
        }
        return Path.of(context.getProperty("sling.home"), "entity-index",
            StringUtils.strip(this.homepage, "/").replace('/', '_'));
    }
}
