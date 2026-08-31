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

import java.lang.reflect.Field;
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
 * Tests for {@link StoredNotificationDelivery}: what gets written, what its recipient is granted, and how every
 * way of failing turns into a declined delivery rather than a broken workflow.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StoredNotificationDeliveryTest
{
    private static final String TEMPLATE = "/libs/iap/notificationTemplates/approved";

    // JCR-backed: the prefix-tree buckets are real nodes
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final StoredNotificationDelivery delivery = new StoredNotificationDelivery();

    /** Every principal granted something, with the privileges spelled out. */
    private final List<String> granted = new ArrayList<>();

    private Resource submission;

    private Recipient requester;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(StoredNotifications.PATH,
            "sling:resourceType", StoredNotifications.HOMEPAGE_RESOURCE_TYPE);
        this.submission = this.context.create().resource("/Submissions/one", "title", "A long weekend");
        final var users = ((JackrabbitSession) this.context.resourceResolver().adaptTo(Session.class))
            .getUserManager();
        final var user = users.createUser("the-requester", "pw");
        this.context.resourceResolver().commit();
        this.requester = new Recipient("the-requester",
            this.context.resourceResolver().getResource(user.getPath()));
        this.factory(this.recordingAccessControl());
    }

    @Test
    void storesTheRenderedLineWhereItsRecipientWillFindIt()
    {
        this.context.create().resource(TEMPLATE,
            "line", "Your request “${subjectTitle}” was ${event} for ${days} days");

        assertTrue(this.delivery.deliver(NotificationContext.about(this.submission)
            .becauseOf("approved")
            .by("an-approver")
            .using(TEMPLATE)
            .with("days", 3)
            .build(), this.requester));

        final Resource stored = this.storedNotification();
        assertNotNull(stored);
        assertEquals("Your request “A long weekend” was approved for 3 days",
            stored.getValueMap().get(StoredNotifications.LINE, String.class));
        assertEquals("the-requester", stored.getValueMap().get(StoredNotifications.RECIPIENT, String.class));
        assertEquals("approved", stored.getValueMap().get("event", String.class));
        assertEquals("/Submissions/one", stored.getValueMap().get("subject", String.class));
        assertEquals(NotificationContext.IMMEDIATE, stored.getValueMap().get("urgency", String.class));
        assertEquals("an-approver", stored.getValueMap().get("actor", String.class));
    }

    @Test
    void grantsTheRecipientTheirNotification()
    {
        this.context.create().resource(TEMPLATE, "line", "It happened");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));

        assertEquals(List.of("the-requester: " + javax.jcr.security.Privilege.JCR_READ + ","
            + javax.jcr.security.Privilege.JCR_MODIFY_PROPERTIES), this.granted);
    }

    // No wording folder at all: the subject can still say what it is about
    @Test
    void fallsBackToTheSubjectsTitleAndTheEvent()
    {
        // Raised by nobody - a deadline, say - which is also fine to store
        assertTrue(this.delivery.deliver(
            NotificationContext.about(this.submission).becauseOf("approved").build(), this.requester));

        final Resource stored = this.storedNotification();
        assertEquals("A long weekend: approved",
            stored.getValueMap().get(StoredNotifications.LINE, String.class));
        assertNull(stored.getValueMap().get("actor", String.class));
    }

    // A named folder that is missing, or one carrying no line, reads the same as none
    @Test
    void fallsBackWhenTheWordingFolderSaysNothing()
    {
        this.context.create().resource(TEMPLATE, "someOtherProperty", "yes");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
        assertTrue(this.delivery.deliver(
            this.notification("/libs/iap/notificationTemplates/gone"), this.requester));

        assertEquals("A long weekend: approved",
            this.storedNotification().getValueMap().get(StoredNotifications.LINE, String.class));
    }

    // A typo stays visible in the list instead of vanishing
    @Test
    void leavesAnUnknownPlaceholderAsWritten()
    {
        this.context.create().resource(TEMPLATE, "line", "${nonsense} was ${event}");

        assertTrue(this.delivery.deliver(this.notification(TEMPLATE), this.requester));

        assertEquals("${nonsense} was approved",
            this.storedNotification().getValueMap().get(StoredNotifications.LINE, String.class));
    }

    // A wording folder may say the empty thing, which is still nothing to list
    @Test
    void declinesABlankLine()
    {
        this.context.create().resource(TEMPLATE, "line", "  ");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // Nothing to say is a normal decline, not a failure
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
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        this.inject(broken);
        this.context.create().resource(TEMPLATE, "line", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // A notification its recipient cannot see is not delivered, it is lost - so the whole delivery is declined
    // and the uncommitted write is discarded with the session (which the mock repository cannot show, since it
    // has no transient space to discard)
    @Test
    void declinesWhenTheGrantFails()
    {
        this.factory(this.recordingAccessControl());
        this.context.create().resource(TEMPLATE, "line", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE),
            new Recipient("ghost", this.submission)));

        assertTrue(this.granted.isEmpty());
    }

    @Test
    void declinesWithoutAUserStore() throws Exception
    {
        // A session that stores content fine but cannot manage access control: the write succeeds and the grant
        // is what fails, so the delivery is declined all the same
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
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(bare);
        this.inject(factory);
        this.context.create().resource(TEMPLATE, "line", "It happened");

        assertFalse(this.delivery.deliver(this.notification(TEMPLATE), this.requester));
    }

    // The repository may hand the list over as an applicable policy for a node that never had one
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
        this.context.create().resource(TEMPLATE, "line", "It happened");

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
        this.context.create().resource(TEMPLATE, "line", "It happened");

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
        return this.find(this.context.resourceResolver().getResource(StoredNotifications.PATH), 0);
    }

    private Resource find(final Resource under, final int depth)
    {
        if (depth == PrefixTree.LEVELS) {
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
            final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
            Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(wrapped);
            this.inject(factory);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void inject(final ResourceResolverFactory factory) throws IllegalStateException
    {
        try {
            final Field field = StoredNotificationDelivery.class.getDeclaredField("resolverFactory");
            field.setAccessible(true);
            field.set(this.delivery, factory);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

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
