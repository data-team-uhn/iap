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
package io.uhndata.iap.workflows.internal;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Turns a workflow's own declarations into read access on the resource it drives.
 *
 * <p>Acting is authorized by the definitions ({@link PerformerCheck}), but reading cannot be: a listing or a query
 * returns rows, and no engine can run a workflow per row to decide whether each may be seen. So reads stay the
 * repository's business, and this is what keeps the repository's answer in step with the workflow's — the policy is
 * still declared by the definition, it is just <em>materialized</em> as an access control list when the instance
 * starts.</p>
 *
 * <p>Who that comes to: the person the instance is being run for, and the performers of every user task in the
 * workflow. Which is to say, the people the process involves — a requester keeps sight of what they raised, and an
 * approver can see what is waiting for them before they open it. Deriving this from {@code performers} rather than
 * inventing a second vocabulary means the two can never disagree; the cost is that access is granted for the whole
 * life of the instance rather than only while a task is open, which is a refinement for when there is a reason to
 * want it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class HostAccess
{
    private HostAccess()
    {
    }

    /**
     * Grants read access on a host resource to everyone its workflow involves.
     *
     * @param resolver the engine's own session
     * @param host the resource the workflow drives
     * @param version the workflow being instantiated
     * @param actor the user the instance is being run for
     * @throws PersistenceException when the access control list cannot be written
     */
    static void grantReaders(final ResourceResolver resolver, final Resource host, final WorkflowVersion version,
        final String actor) throws PersistenceException
    {
        final Set<String> readers = new LinkedHashSet<>();
        readers.add(actor);
        readers.addAll(version.getAllFlowNodes().stream()
            .filter(node -> node instanceof Activity && ((Activity) node).getHandler() == null)
            .flatMap(node -> node.getPerformers().stream())
            .collect(Collectors.toSet()));
        grant(resolver, host.getPath(), readers);
    }

    /**
     * Adds a read entry for each named principal to the resource's access control list.
     *
     * @param resolver the engine's own session
     * @param path the resource to grant on
     * @param principals the principals to admit
     * @throws PersistenceException when the access control list cannot be written
     */
    private static void grant(final ResourceResolver resolver, final String path, final Set<String> principals)
        throws PersistenceException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            throw new PersistenceException("The repository cannot be asked who its users are");
        }
        try {
            final AccessControlManager manager = session.getAccessControlManager();
            final AccessControlList acl = listFor(manager, path);
            final Privilege[] read = new Privilege[] {manager.privilegeFromName(Privilege.JCR_READ)};
            for (final String name : principals) {
                final Principal principal = principalOf((JackrabbitSession) session, name);
                if (principal != null) {
                    acl.addAccessControlEntry(principal, read);
                }
            }
            manager.setPolicy(path, acl);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not grant read access on " + path, e);
        }
    }

    /**
     * The resource's own access control list, whether it already has one or is getting its first.
     *
     * @param manager the repository's access control manager
     * @param path the resource
     * @return a modifiable list
     * @throws RepositoryException when the repository refuses
     */
    private static AccessControlList listFor(final AccessControlManager manager, final String path)
        throws RepositoryException
    {
        for (final AccessControlPolicy policy : manager.getPolicies(path)) {
            if (policy instanceof AccessControlList) {
                return (AccessControlList) policy;
            }
        }
        final AccessControlPolicyIterator applicable = manager.getApplicablePolicies(path);
        while (applicable.hasNext()) {
            final AccessControlPolicy policy = applicable.nextAccessControlPolicy();
            if (policy instanceof AccessControlList) {
                return (AccessControlList) policy;
            }
        }
        throw new RepositoryException("The node at " + path + " cannot hold an access control list");
    }

    /**
     * The principal behind a user or group name.
     *
     * @param session the engine's session
     * @param name the authorizable's id
     * @return the principal, or {@code null} if there is nobody by that name — a definition may well name a group
     *         a given deployment has not created
     * @throws RepositoryException when the user store cannot be read
     */
    private static Principal principalOf(final JackrabbitSession session, final String name)
        throws RepositoryException
    {
        final Authorizable authorizable = session.getUserManager().getAuthorizable(name);
        return authorizable == null ? null : authorizable.getPrincipal();
    }
}
