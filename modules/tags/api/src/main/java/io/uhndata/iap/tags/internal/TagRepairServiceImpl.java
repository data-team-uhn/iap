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
 * Marks stale nodes and lets the propagation editor recompute them. See {@link TagRepairService} for why this is
 * needed at all, and {@link TagPropagationEditor} for what happens once a node is marked.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TagRepairService.class)
public class TagRepairServiceImpl implements TagRepairService
{
    /**
     * Nodes whose derived tags cannot be trusted, whatever the reason recorded on them; the marker is indexed, so
     * this is not a scan. Testing for presence rather than for {@code failed} matters: it also picks up nodes left
     * at {@code recomputing} by a commit that was interrupted before the editor got to them, which nothing else
     * would ever come back for.
     */
    static final String STALE_QUERY =
        "SELECT * FROM [nt:base] WHERE [" + TagManager.COMPUTATION_STATE_PROPERTY + "] IS NOT NULL";

    /** How many nodes are marked before saving. Bounds how much a single refused save can cost. */
    static final int BATCH_SIZE = 200;

    /**
     * What a tag name may look like. Names come from node names under {@code /Tags}, so this rejects nothing that
     * could legitimately be one, and it is checked rather than escaped because a name that fails it is a bug or an
     * attack, not a value to be quoted and passed on.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9_-]+");

    private static final Logger LOGGER = LoggerFactory.getLogger(TagRepairServiceImpl.class);

    /**
     * The repair has a service user of its own rather than borrowing the tag manager's, which may only ever read the
     * definitions. This one may not create or remove anything either: marking a node is a property write, and the
     * recomputation that follows is the commit hook's work, done with the repository's own privileges.
     */
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
        // All four properties are indexed, and a comparison against a multi-valued property holds when any of its
        // values match, so this finds both the content the tag was placed on and the content it was copied to
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
            // A report of nothing repaired is what a healthy repository returns too, so the scheduled sweep would
            // go on reporting success while every stale node stayed stale
            ErrorLogger.logError(e, ErrorContext.of(TagRepairServiceImpl.class, "repair"));
            return new RepairReport(0, 0);
        }
    }

    /**
     * Marks everything the query found, saving in batches. A node that cannot be marked is counted and skipped: one
     * unwritable node must not stop the rest of the repository from being repaired.
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
     * Asks for one node to be recomputed, by recording that its stored tags are untrustworthy.
     *
     * <p>
     * What reaches the editor is the <em>change</em>, not the value: a commit that writes nothing new to a node never
     * visits it, and the node is never recomputed. Writing {@link TagManager#STATE_RECOMPUTING} differs from both
     * states a node can be found in — absent, or {@link TagManager#STATE_FAILED} — so in the ordinary case this is
     * simply a write.
     * </p>
     *
     * <p>
     * The exception is a node already sitting at {@code recomputing}, which should not be possible: the request and
     * the recomputation happen in the same commit, so that value is not observable at rest. Finding one means a
     * commit was interrupted in between, and writing the same value again would leave it stranded for good. Clearing
     * the property is what gets the editor there, since it recomputes a node marked before the commit as readily as
     * one marked during it.
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
            // The count comes back in the report, which a caller who asked for a repair does read — but the
            // scheduled sweep is nobody's to read, and that is the path this runs on unattended
            ErrorLogger.logError(e, ErrorContext.of(TagRepairServiceImpl.class, "saveBatch")
                .with("batchSize", pending));
            return pending;
        }
    }
}
