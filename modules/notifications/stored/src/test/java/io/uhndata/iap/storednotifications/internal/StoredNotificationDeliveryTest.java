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
package io.uhndata.iap.storednotifications.internal;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.storednotifications.api.StoredNotifications;
import io.uhndata.iap.utils.PrefixTree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StoredNotificationDelivery}: what it stores, what it grants the recipient, and that each failure
 * declines the delivery.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StoredNotificationDeliveryTest
{
    private static final String TEMPLATE = "/libs/iap/notificationTemplates/approved";

    // JCR-backed, because the delivery creates the folders and buckets as JCR nodes
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    /** The delivery's only way into the repository; each test decides what it hands out. */
    private final ResourceResolverFactory resolverFactory = Mockito.mock(ResourceResolverFactory.class);

    private StoredNotificationDelivery delivery;

    /** Every principal granted something, with the privileges spelled out. */
    private final List<String> granted = new ArrayList<>();

    private Resource submission;

    private Recipient requester;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(StoredNotifications.HOMEPAGE_PATH,
            "sling:resourceType", StoredNotifications.HOMEPAGE_RESOURCE_TYPE);
        this.submission = this.context.create().resource("/Submissions/one", "title", "A long weekend");
        final var users = ((JackrabbitSession) this.context.resourceResolver().adaptTo(Session.class))
            .getUserManager();
        final var user = users.createUser("the-requester", "pw");
        this.context.resourceResolver().commit();
        this.requester = new Recipient("the-requester",
            this.context.resourceResolver().getResource(user.getPath()));
        this.factory(this.recordingAccessControl());
        // Outranks the factory the test context registers
        this.context.registerService(ResourceResolverFactory.class, this.resolverFactory,
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        this.delivery = this.context.registerInjectActivateService(new StoredNotificationDelivery());
    }

    @Test
    void storesTheRenderedMessageWhereItsRecipientWillFindIt()
    {
        this.context.create().resource(TEMPLATE,
            "uiMessage", "Your request “${subjectTitle}” was ${event} for ${days} days");

        assertTrue(this.delivery.deliver(NotificationContext.about(this.submission)
            .becauseOf("approved")
            .by("an-approver")
            .using(TEMPLATE)
            .with("days", 3)
            .build(), this.requester));

        final Resource stored = this.storedNotification();
        assertNotNull(stored);
        assertEquals("Your request “A long weekend” was approved for 3 days",
            stored.getValueMap().get(StoredNotifications.MESSAGE_PROPERTY, String.class));
        assertEquals("the-requester", stored.getValueMap().get(StoredNotifications.RECIPIENT_PROPERTY, String.class));
        assertEquals("approved", stored.getValueMap().get("event", String.class));
        assertEquals("/Submissions/one", stored.getValueMap().get("subject", String.class));
        assertEquals(NotificationContext.IMMEDIATE_URGENCY, stored.getValueMap().get("urgency", String.class));
        assertEquals("an-approver", stored.getValueMap().get("actor", String.class));
    }

    @Test
    void grantsTheRecipientTheirOwnFolder()
    {
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));

        assertEquals(List.of("the-requester: " + javax.jcr.security.Privilege.JCR_READ + ","
            + javax.jcr.security.Privilege.JCR_MODIFY_PROPERTIES), this.granted);
        assertNotNull(this.context.resourceResolver()
            .getResource(StoredNotifications.HOMEPAGE_PATH + "/the-requester"));
    }

    @Test
    void grantsTheFolderOnlyOnce()
    {
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));

        assertEquals(1, this.granted.size());
    }

    @Test
    void fallsBackToTheSubjectsTitleAndTheEvent()
    {
        // No template, and no actor
        assertTrue(this.delivery.deliver(
            NotificationContext.about(this.submission).becauseOf("approved").build(), this.requester));

        final Resource stored = this.storedNotification();
        assertEquals("A long weekend: approved",
            stored.getValueMap().get(StoredNotifications.MESSAGE_PROPERTY, String.class));
        assertNull(stored.getValueMap().get("actor", String.class));
    }

    // A template folder with no uiMessage, then a template folder that is missing
    @Test
    void fallsBackWhenTheTemplateSaysNothing()
    {
        this.context.create().resource(TEMPLATE, "someOtherProperty", "yes");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
        assertTrue(this.delivery.deliver(
            this.notification("/libs/iap/notificationTemplates/gone"), this.requester));

        assertEquals("A long weekend: approved",
            this.storedNotification().getValueMap().get(StoredNotifications.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void leavesAnUnknownPlaceholderAsWritten()
    {
        this.context.create().resource(TEMPLATE, "uiMessage", "${nonsense} was ${event}");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));

        assertEquals("${nonsense} was approved",
            this.storedNotification().getValueMap().get(StoredNotifications.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void declinesABlankMessage()
    {
        this.context.create().resource(TEMPLATE, "uiMessage", "  ");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // No template, and a subject with no title
    @Test
    void declinesWhenThereIsNothingToList()
    {
        final Resource untitled = this.context.create().resource("/Submissions/untitled");
        assertFalse(this.delivery.deliver(NotificationContext.about(untitled).becauseOf("approved").build(),
            this.requester));
        assertNull(this.storedNotification());
    }

    @Test
    void declinesWhenTheChannelCannotLogIn() throws Exception
    {
        Mockito.when(this.resolverFactory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // The recipient has no account to grant to. JCR_MOCK has no transient space, so the test cannot check that
    // the folder created before the grant is discarded.
    @Test
    void declinesWhenTheGrantFails()
    {
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE),
            new Recipient("ghost", this.submission)));

        assertTrue(this.granted.isEmpty());
    }

    @Test
    void declinesWithoutAUserStore() throws Exception
    {
        // The session is not a Jackrabbit one, so it cannot grant the recipient their folder
        final Session plain = Mockito.mock(Session.class);
        final ResourceResolver bare = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? type.cast(plain) : super.adaptTo(type);
            }

            @Override
            public void close()
            {
                // The test context owns the real resolver
            }
        };
        Mockito.when(this.resolverFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(bare);
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // A node that never had an access control list offers a new one as an applicable policy
    @Test
    void takesAnApplicablePolicyWhenNoneIsSetYet() throws Exception
    {
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        final AccessControlList acl = this.recordingList();
        Mockito.when(manager.getPolicies(Mockito.anyString())).thenReturn(new AccessControlPolicy[0]);
        final AccessControlPolicyIterator applicable = Mockito.mock(AccessControlPolicyIterator.class);
        Mockito.when(applicable.hasNext()).thenReturn(true, false);
        Mockito.when(applicable.nextAccessControlPolicy()).thenReturn(acl);
        Mockito.when(manager.getApplicablePolicies(Mockito.anyString())).thenReturn(applicable);
        Mockito.when(manager.privilegeFromName(Mockito.anyString()))
            .thenAnswer(call -> privilege(call.getArgument(0)));
        this.factory(manager);
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
        assertEquals(1, this.granted.size());
    }

    @Test
    void declinesWhenNoPolicyCanBePut() throws Exception
    {
        final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
        Mockito.when(manager.getPolicies(Mockito.anyString()))
            .thenReturn(new AccessControlPolicy[] {Mockito.mock(AccessControlPolicy.class)});
        final AccessControlPolicyIterator applicable = Mockito.mock(AccessControlPolicyIterator.class);
        Mockito.when(applicable.hasNext()).thenReturn(true, false);
        Mockito.when(applicable.nextAccessControlPolicy())
            .thenReturn(Mockito.mock(AccessControlPolicy.class));
        Mockito.when(manager.getApplicablePolicies(Mockito.anyString())).thenReturn(applicable);
        this.factory(manager);
        this.context.create().resource(TEMPLATE, "uiMessage", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // -------------------------------------------------------------------------------------------- helpers

    private NotificationContext notification(final String template)
    {
        return NotificationContext.about(this.submission)
            .becauseOf("approved")
            .by("an-approver")
            .using(template)
            .build();
    }

    /** The one stored notification, found through the prefix tree, or {@code null} when nothing was stored. */
    private Resource storedNotification()
    {
        final Resource home = this.context.resourceResolver()
            .getResource(StoredNotifications.HOMEPAGE_PATH + "/the-requester");
        return home == null ? null : this.find(home, 0);
    }

    private Resource find(final Resource under, final int depth)
    {
        if (depth == PrefixTree.BUCKET_LEVELS) {
            final Iterator<Resource> children = under.listChildren();
            return children.hasNext() ? children.next() : null;
        }
        for (final Resource child : under.getChildren()) {
            final Resource found = this.find(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Points the delivery at the test repository, with the given access control manager standing in. */
    private void factory(final AccessControlManager accessControl)
    {
        try {
            final ResourceResolver real = this.context.resourceResolver();
            final Session realSession = real.adaptTo(Session.class);
            final JackrabbitSession session =
                Mockito.mock(JackrabbitSession.class, AdditionalAnswers.delegatesTo(realSession));
            Mockito.doReturn(accessControl).when(session).getAccessControlManager();
            final ResourceResolver wrapped = new ResourceResolverWrapper(real)
            {
                @Override
                public <T> T adaptTo(final Class<T> type)
                {
                    return type == Session.class ? type.cast(session) : super.adaptTo(type);
                }

                @Override
                public void close()
                {
                    // The test context owns the real resolver
                }
            };
            Mockito.when(this.resolverFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(wrapped);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An access control manager that records each entry in {@link #granted} instead of applying it. The tests show
     * which grant was asked for, and not that the repository enforces it.
     *
     * @return the recording manager
     */
    private AccessControlManager recordingAccessControl() throws IllegalStateException
    {
        try {
            final AccessControlList acl = this.recordingList();
            final AccessControlManager manager = Mockito.mock(AccessControlManager.class);
            Mockito.when(manager.getPolicies(Mockito.anyString()))
                .thenReturn(new AccessControlPolicy[] {acl});
            Mockito.when(manager.privilegeFromName(Mockito.anyString()))
                .thenAnswer(call -> privilege(call.getArgument(0)));
            return manager;
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private AccessControlList recordingList() throws RepositoryException
    {
        final AccessControlList acl = Mockito.mock(AccessControlList.class);
        Mockito.when(acl.addAccessControlEntry(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            final Principal principal = invocation.getArgument(0);
            final Privilege[] privileges = invocation.getArgument(1);
            final List<String> names = new ArrayList<>();
            for (final Privilege privilege : privileges) {
                names.add(privilege.getName());
            }
            this.granted.add(principal.getName() + ": " + String.join(",", names));
            return true;
        });
        return acl;
    }

    private static Privilege privilege(final String name)
    {
        final Privilege privilege = Mockito.mock(Privilege.class);
        Mockito.when(privilege.getName()).thenReturn(name);
        return privilege;
    }
}
