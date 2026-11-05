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
package io.uhndata.iap.notifications.internal;

import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * Creates user accounts and groups for the tests. An account can have an email address at {@code profile/email}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Accounts
{
    /** The account property holding an email address. */
    static final String EMAIL_PROPERTY = "profile/email";

    private Accounts()
    {
        // Utility class
    }

    /**
     * Creates a user account, with its user id as its password.
     *
     * @param context the test's Sling context, which must be JCR-backed
     * @param userId the user id of the account to create
     * @param address its email address, or {@code null} for none
     * @return the account
     * @throws Exception if it cannot be created
     */
    static User create(final SlingContext context, final String userId, final String address) throws Exception
    {
        final UserManager users = users(context);
        final User user = users.createUser(userId, userId);
        if (address != null) {
            user.setProperty(EMAIL_PROPERTY,
                context.resourceResolver().adaptTo(Session.class).getValueFactory().createValue(address));
        }
        context.resourceResolver().commit();
        return user;
    }

    /**
     * Creates a group with the given members.
     *
     * @param context the test's Sling context, which must be JCR-backed
     * @param groupId the id of the group to create
     * @param members the existing accounts to put in it
     * @return the group
     * @throws Exception if it cannot be created
     */
    static Group group(final SlingContext context, final String groupId, final User... members) throws Exception
    {
        final Group group = users(context).createGroup(groupId);
        for (final User member : members) {
            group.addMember(member);
        }
        context.resourceResolver().commit();
        return group;
    }

    private static UserManager users(final SlingContext context) throws Exception
    {
        return ((JackrabbitSession) context.resourceResolver().adaptTo(Session.class)).getUserManager();
    }
}
