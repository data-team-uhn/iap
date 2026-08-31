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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One vocabulary for saying <em>who</em>, shared by everything that names people: workflow performers,
 * notification recipients, and whatever comes next.
 *
 * <p>
 * A definition names people three ways, and this service answers all three. A <strong>special name</strong> like
 * {@link #CREATOR} or {@link #ME} stands for somebody only a situation can identify, and is answered by whichever
 * {@link io.uhndata.iap.principals.spi.SpecialNameResolver} claims it — a pluggable vocabulary, so a module can
 * add {@code @commentAuthor} without this service learning what a comment is. A <strong>principal name</strong> —
 * a user id, a group, {@link #EVERYONE} — already says who it means and passes through {@link #resolve} untouched.
 * And a <strong>group</strong> can be asked about both ways: {@link #expandToUsers} enumerates the people in it,
 * {@link #isOneOf} answers for one person without enumerating anybody.
 * </p>
 *
 * <p>
 * The same question also gets asked from the other end — not "who does this definition mean" but "what does the
 * person in front of me act as", so that a listing can match them against properties naming principals. That is
 * {@link #principalsOf}, and {@link #MY_PRINCIPALS} is its name in a request.
 * </p>
 *
 * <p>
 * The check and the expansion are deliberately separate operations, because the repository can answer them in
 * different ways. A group synchronised from an identity provider under dynamic membership has no local node
 * listing its members: whether somebody is in it is written on <em>their</em> account, so checking is cheap while
 * enumerating is a query. Every consumer that hand-rolled one of these picked one mechanism and silently gave the
 * wrong answer through the other; this service owns both, so they cannot drift apart again.
 * </p>
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
     * <p>Answered by {@link #principalsOf} rather than by a resolver, because only a live session can say what it
     * is bound to; there is no situation to hand a resolver that would carry the same answer.</p>
     */
    String MY_PRINCIPALS = "@myPrincipals";

    /** The built-in group every authenticated user is in, by definition rather than by membership. */
    String EVERYONE = "everyone";

    /**
     * Answers the special names in a list of names, leaving everything else untouched.
     *
     * <p>A special name — anything starting with {@code @} — is answered by its registered resolver against the
     * given context, contributing whatever principals it stands for there: possibly several, possibly nobody. A
     * special name no resolver claims contributes nobody, said in the log, since it is most likely a typo in a
     * definition. Anything else is already a principal name and passes through as itself. The result keeps the
     * order names were given in, each principal once.</p>
     *
     * @param names the names to resolve: special names, user ids, groups, in any mix
     * @param context what the special names are being asked about
     * @return the resolved principal names, empty when the names stand for nobody
     */
    @NotNull
    List<String> resolve(@NotNull List<String> names, @NotNull PrincipalContext context);

    /**
     * Answers the special names in a list of names about a subject, with nobody acting: the common shape of the
     * question, spared the context object.
     *
     * @param names the names to resolve: special names, user ids, groups, in any mix
     * @param subject the resource the names are about
     * @return the resolved principal names, empty when the names stand for nobody
     */
    @NotNull
    default List<String> resolve(@NotNull final List<String> names, @Nullable final Resource subject)
    {
        return resolve(names, PrincipalContext.about(subject));
    }

    /**
     * Everything a session acts as: the person's own id, then every principal bound to their session, which is
     * what {@link #MY_PRINCIPALS} stands for.
     *
     * <p>This is the other side of {@link #isOneOf}. A property naming who may act holds principals rather than
     * user ids, so somebody asking which of those properties concern them needs the list itself, not a yes or a
     * no about one name. Read from the bound principals, because those are the one reading that already carries
     * the roles an identity provider synchronises without leaving a group node behind.</p>
     *
     * <p>Never empty for an identified session: somebody bound to nothing else still acts as themselves, so a
     * caller filtering on this answer narrows the question to their own rather than widening it to everybody's.
     * </p>
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
     * <p>A user id contributes itself; a group contributes every user in it, through nested groups too, whether
     * the group is a local node or a dynamic principal an identity provider synchronises. {@link #EVERYONE}
     * contributes nobody — it names every authenticated user by definition, and enumerating a deployment's whole
     * user base is never what a definition meant. A name the repository does not know contributes nobody, said in
     * the log. Order is kept and each person appears once, so "tell the approvers, then the auditors" tells
     * somebody who is both exactly once, as an approver.</p>
     *
     * <p>Only people the repository knows can be listed: an account an identity provider knows but that has never
     * logged in does not exist here yet, and no expansion can find it.</p>
     *
     * @param principals the principal names to expand, typically what {@link #resolve} answered
     * @param resolver a session that may read the user store
     * @return the user ids, empty when the principals name nobody
     * @throws PrincipalLookupException when the repository cannot be asked at all — never for an unknown name,
     *     which merely names nobody
     */
    @NotNull
    List<String> expandToUsers(@NotNull Collection<String> principals, @NotNull ResourceResolver resolver);

    /**
     * Whether one person is among the named principals: themselves, {@link #EVERYONE}, or a group they belong to,
     * through nested groups too, local or dynamic.
     *
     * <p>Fail-closed: an empty list admits nobody, and a name the repository cannot answer for admits nobody
     * through it. The user id is taken at its word — whether such a user exists is the caller's question to ask,
     * which is what lets this answer for {@link #EVERYONE} without a lookup.</p>
     *
     * @param userId the person, as their repository user id
     * @param principals the principal names that grant, typically what {@link #resolve} answered
     * @param resolver a session that may read the user store
     * @return {@code true} if the person is among them
     * @throws PrincipalLookupException when the repository cannot answer at all — never for a merely unknown name
     */
    boolean isOneOf(@NotNull String userId, @NotNull Collection<String> principals,
        @NotNull ResourceResolver resolver);
}
