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
package io.uhndata.iap.profiles.internal;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.spi.security.user.action.AbstractAuthorizableAction;
import org.jetbrains.annotations.NotNull;

/**
 * Gives every new account the two nodes its profile values live in, so that maintaining a profile never means creating
 * anything.
 *
 * <p>
 * The profile service writes under {@code /home/users} with rights restricted by glob to the two subtrees named below,
 * deliberately, so that it can maintain what a person's profile says and nothing else. A restriction of that shape
 * cannot cover the containers themselves: adding a child is authorized against the <em>parent</em> -- the account's
 * home node -- and no glob ending in a container name matches that. Measured on Oak 2.4.0, and it holds for
 * {@code rep:itemNames} too, so the gap is not something a different restriction can close. Creating the containers up
 * front closes it instead, and leaves the least-privilege grant exactly as it is.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ProfileContainersAction extends AbstractAuthorizableAction
{
    /** Holds the facts about the person, and the subtree the write grant is restricted to. */
    static final String PROFILE = "profile";

    /** Holds how they want the application to behave, the second restricted subtree. */
    static final String PREFERENCES = "preferences";

    /**
     * Creates the containers for a newly created account.
     *
     * <p>
     * This is the overload every real account arrives through -- a locally created one, and equally one an identity
     * provider synchronises, since {@code DefaultSyncContext} creates it with a null password. The password-less
     * {@code onCreate(User, Root, NamePathMapper)} is deliberately <em>not</em> overridden: Oak reserves it for
     * <em>system</em> users, which are service accounts that no person owns and no profile describes. Both facts were
     * measured against Oak 2.4.0 rather than read from the javadoc, which says neither.
     * </p>
     *
     * @param user the account that has just been created
     * @param password its password, null when it has none; unused here
     * @param root the tree the creation is pending in, committed by whoever asked for the account
     * @param namePathMapper maps the account's JCR path to the Oak path {@code root} is addressed by
     * @throws RepositoryException if the account's home node cannot be reached
     */
    @Override
    public void onCreate(@NotNull final User user, final String password, @NotNull final Root root,
        @NotNull final NamePathMapper namePathMapper) throws RepositoryException
    {
        // getOakPath answers null for a path it cannot map, which is a broken name rather than a missing account; both
        // end the same way, because neither leaves anywhere to put the containers.
        final String path = namePathMapper.getOakPath(user.getPath());
        final Tree home = path == null ? null : root.getTree(path);
        if (home == null || !home.exists()) {
            // Nothing sane can be done here, and swallowing it would hand back an account whose profile cannot be
            // written. Failing the creation is both louder and safer, and it reaches the caller as its own error --
            // which is why this is not also recorded through the error tracking API.
            throw new RepositoryException(
                "Cannot prepare the profile of " + user.getID() + ": its home node is not readable");
        }
        addContainer(home, PROFILE);
        addContainer(home, PREFERENCES);
    }

    /**
     * Adds one container if it is absent, so that an account reaching this twice is unharmed.
     *
     * @param home the account's home node
     * @param name the container to add
     */
    private void addContainer(@NotNull final Tree home, @NotNull final String name)
    {
        if (!home.hasChild(name)) {
            // nt:unstructured is what Oak's own identity-provider synchronisation leaves behind when a mapped claim
            // like `profile/email` creates this node as a side effect, so an account that was synced before this
            // action existed already agrees with what it makes.
            home.addChild(name).setProperty(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_UNSTRUCTURED, Type.NAME);
        }
    }
}
