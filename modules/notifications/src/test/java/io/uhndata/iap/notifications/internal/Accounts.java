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
 * Makes the accounts a notification is addressed to, in the shape the platform reads them: an address at
 * {@code profile/email}, which is where Keycloak's sync handler puts it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Accounts
{
    /** Where an account's email address lives, as Keycloak's sync handler writes it. */
    static final String EMAIL_PROPERTY = "profile/email";

    private Accounts()
    {
        // Utility class
    }

    /**
     * One user account.
     *
     * @param context the test's Sling context, which must be JCR-backed
     * @param userId the account to create
     * @param address its email address, or {@code null} for an account carrying none
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
     * A group with the given members, each of which is created.
     *
     * @param context the test's Sling context
     * @param groupId the group to create
     * @param members the accounts to put in it
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
