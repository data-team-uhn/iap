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
package io.uhndata.iap.principals.api;

import java.util.Collection;
import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Says who a list of names stands for. A name is a user id, a group, or a special name such as
 * {@link #CREATOR_SPECIAL_NAME}, which stands for somebody only a situation can identify.
 *
 * <p>{@link #resolve} answers the special names. Each goes to the
 * {@link io.uhndata.iap.principals.spi.SpecialNameResolver} that claims it, and a bundle adds a name by registering
 * one. {@link #expandToUsers} lists the people a set of principals names, and {@link #isOneOf} says whether one
 * person is among them.</p>
 *
 * <p>{@link #principalsOf} goes the other way: it lists everything one session acts as. A query can then match a
 * person against properties that name principals.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface PrincipalService
{
    /** The special name for the user who created the resource in question. */
    String CREATOR_SPECIAL_NAME = "@creator";

    /**
     * The special name for the user who created the entity the resource in question belongs to, such as the
     * submission an answer is part of.
     */
    String ENTITY_CREATOR_SPECIAL_NAME = "@entityCreator";

    /** The special name for the user who is acting. */
    String ME_SPECIAL_NAME = "@me";

    /**
     * The special name for everything the person asking acts as: themselves, and every group they are in.
     *
     * <p>{@link #principalsOf} answers it, not a resolver. Only the session knows what it is bound to, and a
     * {@link PrincipalContext} carries no session. Passed to {@link #resolve}, it names nobody.</p>
     */
    String MY_PRINCIPALS = "@myPrincipals";

    /** The built-in group every user is in, by definition rather than by membership. */
    String EVERYONE_GROUP = "everyone";

    /**
     * Answers the special names in a list of names, and passes every other name through as it is.
     *
     * <p>A name a registered resolver claims is replaced by that resolver's answer: several principals, or none. A
     * resolver that throws names nobody. A name starting with {@code @} that no resolver claims names nobody, and
     * is logged as a warning. The names keep their order, and each principal appears once.</p>
     *
     * @param names the names to resolve: special names, user ids and groups, in any mix
     * @param context what the special names are asked about
     * @return the principal names; empty if the names stand for nobody
     */
    @NotNull
    List<String> resolve(@NotNull List<String> names, @NotNull PrincipalContext context);

    /**
     * Everything a session acts as, which is what {@link #MY_PRINCIPALS} stands for: the person's own user id, then
     * every principal bound to the session. These include the roles an identity provider synchronises.
     *
     * <p>A resolver with no JCR session is bound to nothing, and acts only as its own user.</p>
     *
     * @param resolver the session to describe
     * @return the user id first, then the principal names, each once; empty only if the resolver has no user id
     * @throws PrincipalLookupException if the session cannot say what it is bound to
     */
    @NotNull
    List<String> principalsOf(@NotNull ResourceResolver resolver);

    /**
     * The people the given principals name, with each group replaced by its members.
     *
     * <p>A user id contributes itself. A group contributes every user in it, directly or through a nested group,
     * whether it is a local node or a role an identity provider synchronises. {@link #EVERYONE_GROUP} stands for
     * every user, and this never lists the whole user base, so it contributes nobody. A name the repository does
     * not know contributes nobody, and so does a name whose lookup fails. All three are logged. The people keep
     * the order of the names, and each appears once, at their first place.</p>
     *
     * <p>Only people the repository knows can be listed. An account an identity provider knows but that has never
     * logged in does not exist in Oak yet.</p>
     *
     * @param principals user ids and group names
     * @param resolver a session that may read the user store
     * @return the user ids; empty if the principals name nobody
     * @throws PrincipalLookupException if the resolver has no user store; never for an unknown name
     */
    @NotNull
    List<String> expandToUsers(@NotNull Collection<String> principals, @NotNull ResourceResolver resolver);

    /**
     * Whether one person is among the named principals: named by their user id, covered by
     * {@link #EVERYONE_GROUP}, or in a named group. The group may be a local node or a role an identity provider
     * synchronises, and the person may be in it directly or through a nested group.
     *
     * <p>An empty list admits nobody, and so does a name the repository does not know. Naming the user id or
     * {@link #EVERYONE_GROUP} admits the person without a lookup, whether or not their account exists. A group
     * admits only an account the repository knows.</p>
     *
     * @param userId the person's user id
     * @param principals user ids and group names
     * @param resolver a session that may read the user store
     * @return {@code true} if the person is among them
     * @throws PrincipalLookupException if the resolver has no user store, or reading it fails; never for an unknown
     *             name
     */
    boolean isOneOf(@NotNull String userId, @NotNull Collection<String> principals,
        @NotNull ResourceResolver resolver);
}
