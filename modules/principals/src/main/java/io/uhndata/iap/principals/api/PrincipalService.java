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
 * One vocabulary for saying who, shared by everything that names people: workflow performers, notification
 * recipients, and whatever comes next.
 *
 * <p>A definition names people three ways. A special name such as {@link #CREATOR} stands for somebody only a
 * situation can identify, and whichever {@link io.uhndata.iap.principals.spi.SpecialNameResolver} claims it
 * answers it. A module can add {@code @commentAuthor} without this service learning what a comment is. A
 * principal name already says who it means and passes through {@link #resolve} untouched. A group can be asked
 * about both ways: {@link #expandToUsers} enumerates its people, {@link #isOneOf} answers for one person.</p>
 *
 * <p>{@link #principalsOf} asks from the other end: what the person in front of you acts as, so a listing can
 * match them against properties naming principals. {@link #MY_PRINCIPALS} is its name in a request.</p>
 *
 * <p>The check and the expansion are separate because the repository answers them differently. A group
 * synchronised from an identity provider under dynamic membership has no local node listing its members.
 * Membership is written on the account, so checking is cheap and enumerating is a query. A consumer that
 * hand-rolls one of these gets the other wrong.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface PrincipalService
{
    /** The special name standing for whoever raised the resource in question. */
    String CREATOR = "@creator";

    /** The special name standing for whoever is acting right now. */
    String ME = "@me";

    /**
     * The special name standing for everything whoever is asking acts as: themselves, and every group they are in.
     *
     * <p>Answered by {@link #principalsOf} rather than by a resolver. Only a live session can say what it is
     * bound to, and there is no situation object that would carry the same answer.</p>
     */
    String MY_PRINCIPALS = "@myPrincipals";

    /** The built-in group every authenticated user is in, by definition rather than by membership. */
    String EVERYONE = "everyone";

    /**
     * Answers the special names in a list of names, leaving everything else untouched.
     *
     * <p>Anything starting with {@code @} goes to its registered resolver, which contributes whatever it stands
     * for in this context: several principals, or none. A special name no resolver claims contributes nobody and
     * says so in the log, since it is most likely a typo. Everything else passes through as itself. The order
     * names were given in is kept, each principal once.</p>
     *
     * @param names the names to resolve: special names, user ids, groups, in any mix
     * @param context what the special names are being asked about
     * @return the resolved principal names, empty when the names stand for nobody
     */
    @NotNull
    List<String> resolve(@NotNull List<String> names, @NotNull PrincipalContext context);

    /**
     * Everything a session acts as: the person's own id, then every principal bound to their session, which is
     * what {@link #MY_PRINCIPALS} stands for.
     *
     * <p>The other side of {@link #isOneOf}. A property naming who may act holds principals, not user ids, so
     * somebody asking which of those properties concern them needs the list, not a yes or no about one name. Read
     * from the session's bound principals, the one reading that carries roles an identity provider synchronises
     * without leaving a group node behind.</p>
     *
     * <p>Never empty for an identified session. Somebody bound to nothing else still acts as themselves, so a
     * caller filtering on this narrows the question rather than widening it.</p>
     *
     * @param resolver the session to describe
     * @return the principal names, the person's own id first
     * @throws PrincipalLookupException when the session cannot say what it is bound to
     */
    @NotNull
    List<String> principalsOf(@NotNull ResourceResolver resolver);

    /**
     * The people the given principals name, with groups expanded into their members.
     *
     * <p>A user id contributes itself. A group contributes every user in it, through nested groups, whether it
     * is a local node or a dynamic principal an identity provider synchronises. {@link #EVERYONE} contributes
     * nobody: it names every authenticated user by definition, and no definition means the whole user base. A
     * name the repository does not know contributes nobody and says so in the log. Order is kept and each person
     * appears once, so "tell the approvers, then the auditors" tells somebody who is both once, as an approver.</p>
     *
     * <p>Only people the repository knows can be listed. An account an identity provider knows but that has never
     * logged in does not exist here yet.</p>
     *
     * @param principals the principal names to expand, typically what {@link #resolve} answered
     * @param resolver a session that may read the user store
     * @return the user ids, empty when the principals name nobody
     * @throws PrincipalLookupException when the repository cannot be asked at all, never for an unknown name
     */
    @NotNull
    List<String> expandToUsers(@NotNull Collection<String> principals, @NotNull ResourceResolver resolver);

    /**
     * Whether one person is among the named principals: themselves, {@link #EVERYONE}, or a group they belong to,
     * through nested groups too, local or dynamic.
     *
     * <p>Fail-closed: an empty list admits nobody, and so does a name the repository cannot answer for. The
     * user id is taken at its word, since whether such a user exists is the caller's question. That is what lets
     * {@link #EVERYONE} be answered without a lookup.</p>
     *
     * @param userId the person, as their repository user id
     * @param principals the principal names that grant, typically what {@link #resolve} answered
     * @param resolver a session that may read the user store
     * @return {@code true} if the person is among them
     * @throws PrincipalLookupException when the repository cannot answer at all, never for an unknown name
     */
    boolean isOneOf(@NotNull String userId, @NotNull Collection<String> principals,
        @NotNull ResourceResolver resolver);
}
