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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.RestoreResult;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;
import io.uhndata.iap.links.api.LinkManager;

/**
 * Deletes, restores and purges through the {@code iap-deletion} service user's session. That session finds the
 * referrers the requesting user cannot see, and can write to the archive. Permissions are checked against the
 * requesting user's own session, and the guards are told who that user is. {@link #deleteWithin} writes through the
 * caller's resolver instead, and commits nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class DeletionServiceImpl implements DeletionService
{
    /** The subservice name that the feature maps to the {@code iap-deletion} service user. */
    public static final String SUBSERVICE_NAME = "deletion";

    @Reference
    private ResourceResolverFactory resolverFactory;

    // Not used here. The Link models remove links through the links module, and this reference makes this
    // component wait until that module is active.
    @Reference
    @SuppressWarnings("checkstyle:UnusedPrivateField")
    private LinkManager linkManager;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<DeletionVeto> vetoes;

    @Override
    public DeletionImpact analyze(final Resource item, final DeletionOptions options)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            return this.buildImpact(this.resolvePlan(item, options, serviceResolver));
        } catch (final RepositoryException e) {
            throw new DeletionException("Failed to analyze the deletion of " + item.getPath(), e);
        }
    }

    @Override
    public DeletionResult delete(final Resource item, final DeletionOptions options)
    {
        return this.execute(item, options, serviceResolver -> serviceResolver, DeletionServiceImpl::commit);
    }

    @Override
    public DeletionResult deleteWithin(final Resource item, final DeletionOptions options)
    {
        return this.execute(item, options, serviceResolver -> item.getResourceResolver(),
            DeletionServiceImpl::leavePending);
    }

    /**
     * Plans a deletion, refuses it when anything forbids it, and otherwise carries it out.
     *
     * @param item the resource to delete, read through the requesting user's resolver
     * @param options how to delete
     * @param writer given the service resolver, returns the resolver to make the changes through
     * @param execution carries the deletion out through that resolver
     * @return the outcome
     */
    private DeletionResult execute(final Resource item, final DeletionOptions options,
        final UnaryOperator<ResourceResolver> writer, final Execution execution)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            final DeletionPlan plan = this.resolvePlan(item, options, serviceResolver);
            final DeletionImpact impact = this.buildImpact(plan);
            if (!impact.getVetoes().isEmpty()) {
                return new DeletionResult(DeletionResult.Status.VETOED, null, impact);
            }
            if (!impact.isExecutable()) {
                return new DeletionResult(DeletionResult.Status.REQUIRES_CONFIRMATION, null, impact);
            }
            if (plan.isDenied()) {
                return new DeletionResult(DeletionResult.Status.DENIED, null, impact);
            }
            return execution.carryOut(ArchiveOperations.forResolver(writer.apply(serviceResolver)), plan, impact,
                Objects.requireNonNullElse(options.getOnBehalfOf(), plan.getUserSession().getUserID()));
        } catch (final RepositoryException | PersistenceException e) {
            throw new DeletionException("Failed to delete " + item.getPath(), e);
        }
    }

    private static DeletionResult commit(final ArchiveOperations operations, final DeletionPlan plan,
        final DeletionImpact impact, final String requester) throws RepositoryException
    {
        if (plan.getOptions().isPermanent()) {
            operations.deletePermanently(plan);
            return new DeletionResult(DeletionResult.Status.DELETED, null, impact);
        }
        return new DeletionResult(DeletionResult.Status.ARCHIVED, operations.store(plan, requester), impact);
    }

    private static DeletionResult leavePending(final ArchiveOperations operations, final DeletionPlan plan,
        final DeletionImpact impact, final String requester) throws RepositoryException, PersistenceException
    {
        if (plan.getOptions().isPermanent()) {
            operations.deletePermanentlyPending(plan);
            return new DeletionResult(DeletionResult.Status.DELETED, null, impact, operations.checkedOut());
        }
        final String entryPath = operations.storePending(plan, requester);
        return new DeletionResult(DeletionResult.Status.ARCHIVED, entryPath, impact, operations.checkedOut());
    }

    @Override
    public RestoreResult restore(final Resource archiveEntry)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            final Node entry = this.requireEntry(archiveEntry, serviceResolver);
            return ArchiveOperations.forResolver(serviceResolver)
                .restore(entry, this.getUserSession(archiveEntry));
        } catch (final RepositoryException e) {
            throw new DeletionException("Failed to restore " + archiveEntry.getPath(), e);
        }
    }

    @Override
    public DeletionResult purge(final Resource archiveEntry)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            final Node entry = this.requireEntry(archiveEntry, serviceResolver);
            final List<Veto> found = this.sweepPurgeVetoes(entry, archiveEntry);
            final DeletionImpact impact = new DeletionImpact(List.of(entry.getPath()), List.of(), found,
                List.of(), 0, "");
            if (!found.isEmpty()) {
                return new DeletionResult(DeletionResult.Status.VETOED, null, impact);
            }
            ArchiveOperations.forResolver(serviceResolver).purge(entry);
            return new DeletionResult(DeletionResult.Status.DELETED, null, impact);
        } catch (final RepositoryException e) {
            throw new DeletionException("Failed to purge " + archiveEntry.getPath(), e);
        }
    }

    /**
     * Finds the requested resource in the service session, and works out everything its deletion affects.
     */
    private DeletionPlan resolvePlan(final Resource item, final DeletionOptions options,
        final ResourceResolver serviceResolver) throws RepositoryException
    {
        final String path = item.getPath();
        if ("/".equals(path)) {
            throw new IllegalArgumentException("The repository root cannot be deleted");
        }
        if (path.equals(ARCHIVE_PATH) || path.startsWith(ARCHIVE_PATH + "/")) {
            throw new IllegalArgumentException(
                "Archived resources are not deleted directly; restore or purge their archive entry instead");
        }
        if (item.adaptTo(Node.class) == null) {
            throw new IllegalArgumentException("Not a repository resource: " + path);
        }
        final DeletionPlan plan = new DeletionPlan(options, path, this.getUserSession(item), serviceResolver);
        new CascadeResolver(plan, this.currentVetoes()).resolve(serviceSession(serviceResolver).getNode(path));
        return plan;
    }

    private DeletionImpact buildImpact(final DeletionPlan plan)
    {
        final ReferrerReport report = new ReferrerReport(plan);
        return new DeletionImpact(List.copyOf(plan.getRoots().keySet()),
            List.copyOf(plan.getLinksToRemove().keySet()), plan.getVetoes(), report.getGroups(),
            report.getInaccessibleCount(), report.summary());
    }

    private Node requireEntry(final Resource archiveEntry, final ResourceResolver serviceResolver)
        throws RepositoryException
    {
        final Node userView = archiveEntry.adaptTo(Node.class);
        if (userView == null || !userView.isNodeType(ENTRY_NODETYPE)) {
            throw new IllegalArgumentException("Not an archive entry: " + archiveEntry.getPath());
        }
        return serviceSession(serviceResolver).getNode(archiveEntry.getPath());
    }

    private static Session serviceSession(final ResourceResolver serviceResolver)
    {
        final Session session = serviceResolver.adaptTo(Session.class);
        if (session == null) {
            throw new DeletionException("The service resolver is not backed by a repository session", null);
        }
        return session;
    }

    private Session getUserSession(final Resource item)
    {
        final Session session = item.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new DeletionException("The requesting session is not a repository session", null);
        }
        return session;
    }

    private ResourceResolver getServiceResolver()
    {
        try {
            return this.resolverFactory
                .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME));
        } catch (final LoginException e) {
            throw new DeletionException("The deletion service user is not available", e);
        }
    }

    /**
     * The registered guards, copied once per operation. OSGi replaces the injected list whenever a guard comes or
     * goes.
     */
    private List<DeletionVeto> currentVetoes()
    {
        final List<DeletionVeto> current = this.vetoes;
        return current == null ? List.of() : List.copyOf(current);
    }

    @Override
    public List<RestoreConflict> checkRestore(final Resource archiveEntry)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            final Node entry = this.requireEntry(archiveEntry, serviceResolver);
            return ArchiveOperations.forResolver(serviceResolver)
                .evaluateRestore(entry, this.getUserSession(archiveEntry))
                .conflicts();
        } catch (final RepositoryException e) {
            throw new DeletionException("Failed to check restoring " + archiveEntry.getPath(), e);
        }
    }

    @Override
    public List<Veto> checkPurge(final Resource archiveEntry)
    {
        try (ResourceResolver serviceResolver = this.getServiceResolver()) {
            return this.sweepPurgeVetoes(this.requireEntry(archiveEntry, serviceResolver), archiveEntry);
        } catch (final RepositoryException e) {
            throw new DeletionException("Failed to check purging " + archiveEntry.getPath(), e);
        }
    }

    /**
     * Ask every guard whether this entry may be purged. {@link #purge} and {@link #checkPurge} both ask through
     * here, so they cannot disagree.
     *
     * @param entry the entry node, in the service session
     * @param archiveEntry the entry as the requester addressed it, whose session identifies them to the guards
     * @return every objection raised; empty if there are none
     * @throws RepositoryException if the archive cannot be read
     */
    private List<Veto> sweepPurgeVetoes(final Node entry, final Resource archiveEntry) throws RepositoryException
    {
        final List<Veto> found = new ArrayList<>();
        CascadeResolver.sweepVetoes(entry, this.currentVetoes(), DeletionMode.PURGE,
            this.getUserSession(archiveEntry), found);
        return found;
    }

    /**
     * Carries out a planned deletion that nothing forbids.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface Execution
    {
        /**
         * Carries the deletion out.
         *
         * @param operations the operations, bound to the resolver the changes are made through
         * @param plan the plan, which nothing forbids
         * @param impact what the plan removes
         * @param requester the user id the archive entry records as having deleted the resources
         * @return the outcome
         * @throws RepositoryException if the changes cannot be applied
         * @throws PersistenceException if a missing bucket of the archive cannot be added
         */
        DeletionResult carryOut(ArchiveOperations operations, DeletionPlan plan, DeletionImpact impact,
            String requester) throws RepositoryException, PersistenceException;
    }
}
