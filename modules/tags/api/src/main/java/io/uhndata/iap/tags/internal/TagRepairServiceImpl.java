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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.api.TagRepairException;
import io.uhndata.iap.tags.api.TagRepairService;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;

/**
 * Finds the nodes to repair with a query, and marks them in batches of {@value #BATCH_SIZE}. See
 * {@link TagPropagationEditor} for the recomputation.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TagRepairService.class)
public class TagRepairServiceImpl implements TagRepairService
{
    /**
     * The nodes whose derived tags cannot be trusted, whatever the value of the mark. It also finds a node left at
     * {@code recomputing} by an interrupted commit, which nothing else comes back for.
     */
    static final String STALE_QUERY =
        "SELECT * FROM [nt:base] WHERE [" + TagManager.COMPUTATION_STATE_PROPERTY + "] IS NOT NULL";

    /** How many nodes are marked before saving. A refused save loses at most this many marks. */
    static final int BATCH_SIZE = 200;

    /** The query variable the name of the tag to repair is bound to. */
    private static final String TAG_NAME_VARIABLE = "tagName";

    /**
     * The nodes carrying a tag, in the property it is placed in or in any phase's derived property. A comparison with
     * a multivalued property holds when any of its values matches, so this finds the content the tag was placed on,
     * computed for, or copied to.
     */
    private static final String TAGGED_QUERY = Stream.concat(Stream.of(TagManager.TAGS_PROPERTY),
        Arrays.stream(Phase.values()).map(Phase::getPropertyName))
        .map(property -> "[" + property + "] = $" + TAG_NAME_VARIABLE)
        .collect(Collectors.joining(" OR ", "SELECT * FROM [nt:base] WHERE ", ""));

    private static final Logger LOGGER = LoggerFactory.getLogger(TagRepairServiceImpl.class);

    /** The subservice name mapped to the service user allowed to mark any node. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "tagrepair");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    @NotNull
    public RepairReport repairFailed() throws TagRepairException
    {
        return run(session -> createQuery(session, STALE_QUERY));
    }

    @Override
    @NotNull
    public RepairReport repair(@NotNull final String tagName) throws TagRepairException
    {
        return run(session -> {
            final Query query = createQuery(session, TAGGED_QUERY);
            query.bindValue(TAG_NAME_VARIABLE, session.getValueFactory().createValue(tagName));
            return query;
        });
    }

    /**
     * Runs one repair: find the affected nodes, mark them, and let the editor do the rest.
     *
     * @param selection the query selecting the nodes to repair
     * @return what the repair did
     * @throws TagRepairException if the service user cannot log in, or the query cannot run
     */
    private RepairReport run(final Selection selection) throws TagRepairException
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            return markAll(resolver, find(resolver, selection));
        } catch (final LoginException e) {
            throw refusal("the tag repair service user is not available", e);
        }
    }

    /**
     * Finds the paths of the nodes to repair, read in one go so that the query's results are not still being
     * iterated while the batches commit.
     *
     * @param resolver the service user's resolver
     * @param selection the query selecting the nodes to repair
     * @return the paths of the nodes found
     * @throws TagRepairException if the query cannot run
     */
    private static List<String> find(final ResourceResolver resolver, final Selection selection)
        throws TagRepairException
    {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "The service user's resolver is always JCR-backed");
        try {
            final List<String> paths = new ArrayList<>();
            for (final NodeIterator nodes = selection.prepare(session).execute().getNodes(); nodes.hasNext();) {
                paths.add(nodes.nextNode().getPath());
            }
            return paths;
        } catch (final RepositoryException e) {
            throw refusal("the query finding the nodes to repair failed", e);
        }
    }

    private static Query createQuery(final Session session, final String statement) throws RepositoryException
    {
        return session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2);
    }

    /**
     * Logs and records why a repair could not run, and builds the exception telling the caller.
     *
     * @param reason why the repair could not run
     * @param cause the failure that stopped it
     * @return the exception to throw
     */
    private static TagRepairException refusal(final String reason, final Exception cause)
    {
        LOGGER.error("Cannot repair tags, {}: {}", reason, cause.getMessage(), cause);
        // Recorded here, where the cause is known: the scheduled sweep has nobody to tell
        ErrorLogger.logError(cause, ErrorContext.of(TagRepairServiceImpl.class, "repair"));
        return new TagRepairException("Cannot repair tags, " + reason, cause);
    }

    /**
     * Marks every node found, saving in batches.
     *
     * @param resolver the resolver to mark the nodes through
     * @param paths the paths of the nodes to mark
     * @return what the repair did
     */
    private RepairReport markAll(final ResourceResolver resolver, final List<String> paths)
    {
        long marked = 0;
        long failed = 0;
        long pending = 0;
        for (final String path : paths) {
            final Resource resource = resolver.getResource(path);
            if (resource == null) {
                // Removed since the query found it, so there is nothing left to repair
                continue;
            }
            final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
            if (properties == null) {
                failed++;
                LOGGER.warn("Cannot mark {} for tag recomputation, it is not writable", path);
                continue;
            }
            mark(properties);
            marked++;
            pending++;
            if (pending >= BATCH_SIZE) {
                final long lost = save(resolver, pending);
                failed += lost;
                marked -= lost;
                pending = 0;
            }
        }
        final long lost = save(resolver, pending);
        return new RepairReport(marked - lost, failed + lost);
    }

    /**
     * Asks for one node to be recomputed, by marking its stored tags as stale.
     *
     * <p>
     * The editor visits only a node the commit changes, so the mark must change the node. A node is usually found
     * with no mark or with {@link TagManager#STATE_FAILED}, and gets {@link TagManager#STATE_RECOMPUTING}.
     * </p>
     *
     * <p>
     * A node already at {@code recomputing} was left there by an interrupted commit, since the mark is set and
     * removed within one commit. Writing the same value would change nothing. Removing the mark is a change, and the
     * editor recomputes a node that was marked before the commit.
     * </p>
     *
     * @param properties the properties of the node to mark
     */
    private void mark(final ModifiableValueMap properties)
    {
        if (TagManager.STATE_RECOMPUTING.equals(properties.get(TagManager.COMPUTATION_STATE_PROPERTY, String.class))) {
            properties.remove(TagManager.COMPUTATION_STATE_PROPERTY);
        } else {
            properties.put(TagManager.COMPUTATION_STATE_PROPERTY, TagManager.STATE_RECOMPUTING);
        }
    }

    /**
     * Saves one batch, discarding it if the repository refuses it.
     *
     * @param resolver the resolver to commit
     * @param pending how many nodes the batch marked, {@code 0} for an empty trailing batch
     * @return the number of nodes lost, {@code 0} when the batch was saved or was empty
     */
    private long save(final ResourceResolver resolver, final long pending)
    {
        if (pending == 0) {
            return 0;
        }
        try {
            resolver.commit();
            return 0;
        } catch (final PersistenceException e) {
            LOGGER.warn("Cannot save a batch of {} tag repairs: {}", pending, e.getMessage(), e);
            resolver.revert();
            // Recorded, since the scheduled sweep only logs its report
            ErrorLogger.logError(e, ErrorContext.of(TagRepairServiceImpl.class, "saveBatch")
                .with("batchSize", pending));
            return pending;
        }
    }

    /**
     * Prepares the query selecting the nodes a repair marks, in the service user's session.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface Selection
    {
        /**
         * Prepares the query, with any variable it has bound.
         *
         * @param session the service user's session
         * @return the query, ready to run
         * @throws RepositoryException if the query cannot be prepared
         */
        Query prepare(Session session) throws RepositoryException;
    }
}
