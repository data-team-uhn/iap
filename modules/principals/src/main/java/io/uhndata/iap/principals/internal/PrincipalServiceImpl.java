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
package io.uhndata.iap.principals.internal;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.GroupPrincipal;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalLookupException;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.spi.SpecialNameResolver;
import io.uhndata.iap.utils.UserIds;

/**
 * Answers who a list of names stands for, in each of the ways the platform asks.
 *
 * <p>
 * Groups are the interesting half, because the repository stores membership two ways at once. A local group is a
 * node that lists its members, so {@link UserManager} can both check and enumerate it. A group an identity
 * provider synchronises under dynamic membership has no node at all: the roles are written on each member's own
 * account, surfaced as principals, so {@link UserManager} answers {@code null} for the group's very name — while
 * the {@link PrincipalManager} can still say who is in it, both ways. Every lookup here asks the user store first
 * and the principal store second, so a definition does not have to know where a deployment's groups come from.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = PrincipalService.class)
public class PrincipalServiceImpl implements PrincipalService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PrincipalServiceImpl.class);

    /** The registered special-name vocabulary. A dynamic whiteboard, so any bundle may extend it. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<SpecialNameResolver> resolvers;

    @Override
    public List<String> resolve(final List<String> names, final PrincipalContext context)
    {
        final Map<String, SpecialNameResolver> vocabulary = currentResolvers();
        // A LinkedHashSet so that somebody named twice - directly and through a special name, say - is named
        // once, and the order of the definition is the order of the answer
        final Collection<String> found = new LinkedHashSet<>();
        for (final String name : names) {
            final SpecialNameResolver resolver = vocabulary.get(name);
            if (resolver != null) {
                found.addAll(answerOf(resolver, context));
            } else if (name.startsWith("@")) {
                // Most likely a typo in a definition, so it is said rather than silently naming nobody
                LOGGER.warn("Nothing knows who {} stands for, so it names nobody", name);
            } else {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    @Override
    public List<String> principalsOf(final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            // No repository behind this resolver, so nothing is bound to anything: whoever it says is asking is
            // the only answer there is, and answering nobody would widen every caller's question
            return Stream.ofNullable(resolver.getUserID()).toList();
        }
        try {
            return List.copyOf(UserIds.principalsOf(session));
        } catch (final RepositoryException e) {
            throw new PrincipalLookupException("Could not determine what the session acts as", e);
        }
    }

    @Override
    public List<String> expandToUsers(final Collection<String> principals, final ResourceResolver resolver)
    {
        final JackrabbitSession session = sessionOf(resolver);
        final Collection<String> found = new LinkedHashSet<>();
        for (final String name : principals) {
            if (EVERYONE.equals(name)) {
                // Everyone names every authenticated user by definition; enumerating a deployment's whole user
                // base is never what a definition meant, so the name contributes nobody rather than everybody
                LOGGER.warn("The group {} cannot be expanded into people, so it names nobody here", EVERYONE);
                continue;
            }
            try {
                found.addAll(usersOf(name, session));
            } catch (final RepositoryException e) {
                LOGGER.error("Could not expand {} into people: {}", name, e.getMessage(), e);
                ErrorLogger.logError(e, ErrorContext.of(PrincipalServiceImpl.class, "expandToUsers").about(name));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public boolean isOneOf(final String userId, final Collection<String> principals,
        final ResourceResolver resolver)
    {
        if (principals.isEmpty()) {
            return false;
        }
        // Everyone admits any authenticated user by definition; the id is taken at its word, since whether such
        // a user exists is the caller's question
        if (principals.contains(userId) || principals.contains(EVERYONE)) {
            return true;
        }
        final JackrabbitSession session = sessionOf(resolver);
        try {
            if (userId.equals(session.getUserID())) {
                // Asking about the session's own user: the bound principals already carry every membership,
                // dynamic ones included, so this is both the cheap answer and the correct one
                final Set<String> mine = new HashSet<>(principalsOf(resolver));
                return principals.stream().anyMatch(mine::contains);
            }
            return isMemberOfAny(userId, principals, session);
        } catch (final RepositoryException e) {
            throw new PrincipalLookupException("Could not determine what " + userId + " belongs to", e);
        }
    }

    /**
     * The people one principal names: a user themselves, or a group's members, however the group is stored.
     *
     * @param name the principal name to expand
     * @param session a session that may read the user store
     * @return the user ids, empty when the name names nobody
     * @throws RepositoryException when the user store cannot be read
     */
    private static List<String> usersOf(final String name, final JackrabbitSession session)
        throws RepositoryException
    {
        final UserManager users = session.getUserManager();
        final Authorizable authorizable = users.getAuthorizable(name);
        if (authorizable == null) {
            return dynamicMembersOf(name, users, session.getPrincipalManager());
        }
        if (!authorizable.isGroup()) {
            return List.of(authorizable.getID());
        }
        final List<String> ids = new ArrayList<>();
        // getMembers is transitive, so that naming a group also names the members of its member groups
        for (final Iterator<Authorizable> all = ((Group) authorizable).getMembers(); all.hasNext();) {
            final Authorizable member = all.next();
            if (!member.isGroup()) {
                ids.add(member.getID());
            }
        }
        return ids;
    }

    /**
     * The people in a group the user store has no node for, asked of the principal store instead — which is where
     * a group synchronised under dynamic membership answers from, by querying what is written on each member's
     * account.
     *
     * @param name the group's principal name
     * @param users the user store, to turn member principals back into user ids
     * @param principalManager the principal store
     * @return the user ids, empty when the name names nobody there either
     * @throws RepositoryException when the stores cannot be read
     */
    private static List<String> dynamicMembersOf(final String name, final UserManager users,
        final PrincipalManager principalManager) throws RepositoryException
    {
        final Principal principal = principalManager.getPrincipal(name);
        if (!(principal instanceof GroupPrincipal group)) {
            LOGGER.warn("The name {} names nobody in this repository", name);
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        for (final Enumeration<? extends Principal> members = group.members(); members.hasMoreElements();) {
            final Authorizable member = users.getAuthorizable(members.nextElement());
            if (member != null && !member.isGroup()) {
                ids.add(member.getID());
            }
        }
        return ids;
    }

    /**
     * Whether the user belongs to any of the named principals, asked name by name of whichever store knows it.
     *
     * @param userId the user
     * @param principals the principal names that grant
     * @param session a session that may read the user store
     * @return {@code true} if any name admits the user
     * @throws RepositoryException when the stores cannot be read
     */
    private static boolean isMemberOfAny(final String userId, final Collection<String> principals,
        final JackrabbitSession session) throws RepositoryException
    {
        final UserManager users = session.getUserManager();
        final Authorizable user = users.getAuthorizable(userId);
        if (user == null) {
            // Fail-closed: an account the repository does not know belongs to nothing
            return false;
        }
        final PrincipalManager principalManager = session.getPrincipalManager();
        for (final String name : principals) {
            if (admits(name, user, users, principalManager)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one named principal is a group this user belongs to, however the group is stored.
     *
     * @param name the principal name
     * @param user the user
     * @param users the user store
     * @param principalManager the principal store
     * @return {@code true} if the name is a group and the user is in it
     * @throws RepositoryException when the stores cannot be read
     */
    private static boolean admits(final String name, final Authorizable user, final UserManager users,
        final PrincipalManager principalManager) throws RepositoryException
    {
        final Authorizable named = users.getAuthorizable(name);
        if (named != null) {
            // isMember is transitive, matching what expansion enumerates
            return named.isGroup() && ((Group) named).isMember(user);
        }
        final Principal principal = principalManager.getPrincipal(name);
        return principal instanceof GroupPrincipal group && group.isMember(user.getPrincipal());
    }

    /**
     * One resolver's answer, with its failures contained: a broken resolver loses its own name's answer, not the
     * whole list's.
     *
     * @param resolver the resolver to ask
     * @param context what to ask it about
     * @return its answer, empty when it failed
     */
    private static List<String> answerOf(final SpecialNameResolver resolver, final PrincipalContext context)
    {
        try {
            return resolver.resolve(context);
        } catch (final RuntimeException e) {
            LOGGER.error("The resolver for {} failed, so it names nobody: {}", resolver.getName(),
                e.getMessage(), e);
            ErrorLogger.logError(e,
                ErrorContext.of(PrincipalServiceImpl.class, "resolve").about(resolver.getName()));
            return List.of();
        }
    }

    /**
     * The registered vocabulary, keyed by name. Rebuilt per call because the whiteboard is dynamic; with two
     * resolvers claiming one name, which answers is not defined.
     *
     * @return the current resolvers by name
     */
    private Map<String, SpecialNameResolver> currentResolvers()
    {
        final List<SpecialNameResolver> current = this.resolvers;
        return current == null ? Map.of()
            : current.stream().collect(
                Collectors.toMap(SpecialNameResolver::getName, Function.identity(), (first, second) -> first));
    }

    /**
     * The user store behind a resolver, which is the one thing nothing here can do without.
     *
     * @param resolver the session to ask
     * @return its Jackrabbit session
     * @throws PrincipalLookupException when the session is not a Jackrabbit one
     */
    private static JackrabbitSession sessionOf(final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession jackrabbit)) {
            throw new PrincipalLookupException("The session cannot reach the user store", null);
        }
        return jackrabbit;
    }
}
