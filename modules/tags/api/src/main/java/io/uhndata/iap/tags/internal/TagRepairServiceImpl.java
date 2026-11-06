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

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

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
import io.uhndata.iap.tags.api.TagRepairService;

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

    /** The tag names a repair accepts, since the name goes into a query unescaped. */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9_-]+");

    private static final Logger LOGGER = LoggerFactory.getLogger(TagRepairServiceImpl.class);

    /** The subservice name mapped to the service user allowed to mark any node. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "tagrepair");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    @NotNull
    public RepairReport repairFailed()
    {
        return run(STALE_QUERY);
    }

    @Override
    @NotNull
    public RepairReport repair(@NotNull final String tagName)
    {
        if (!SAFE_NAME.matcher(tagName).matches()) {
            LOGGER.warn("Refusing to repair the tag {}: not a tag name", tagName);
            return new RepairReport(0, 0);
        }
        // A comparison with a multivalued property holds when any of its values matches, so this finds the content
        // the tag was placed on, computed for, or copied to
        final String query = "SELECT * FROM [nt:base] WHERE [" + TagManager.TAGS_PROPERTY + "] = '" + tagName + "'"
            + " OR [computedTags] = '" + tagName + "'"
            + " OR [inheritedTags] = '" + tagName + "'"
            + " OR [aggregatedTags] = '" + tagName + "'";
        return run(query);
    }

    /**
     * Runs one repair: find the affected nodes, mark them, and let the editor do the rest.
     *
     * @param query the JCR-SQL2 query selecting the nodes to repair
     * @return what the repair did
     */
    private RepairReport run(final String query)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            return markAll(resolver, resolver.findResources(query, "JCR-SQL2"));
        } catch (final LoginException e) {
            LOGGER.error("Cannot repair tags, the tag repair service user is not available: {}", e.getMessage(), e);
            // Recorded, since the report reads the same as for a repository with nothing to repair
            ErrorLogger.logError(e, ErrorContext.of(TagRepairServiceImpl.class, "repair"));
            return new RepairReport(0, 0);
        }
    }

    /**
     * Marks everything the query found, saving in batches.
     *
     * @param resolver the resolver the resources belong to
     * @param found the resources to mark
     * @return what the repair did
     */
    private RepairReport markAll(final ResourceResolver resolver, final Iterator<Resource> found)
    {
        long marked = 0;
        long failed = 0;
        long pending = 0;
        while (found.hasNext()) {
            final Resource resource = found.next();
            final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
            if (properties == null) {
                failed++;
                LOGGER.warn("Cannot mark {} for tag recomputation, it is not writable", resource.getPath());
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
}
