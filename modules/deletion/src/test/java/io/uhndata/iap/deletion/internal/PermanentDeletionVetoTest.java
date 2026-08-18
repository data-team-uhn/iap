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
package io.uhndata.iap.deletion.internal;

import java.security.Principal;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.spi.DeletionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PermanentDeletionVeto}.
 *
 * @version $Id$
 */
class PermanentDeletionVetoTest
{
    private static final String ALICE = "alice";

    private static final String REVIEWERS = "reviewers";

    private final PermanentDeletionVeto veto = new PermanentDeletionVeto();

    // This guard looks only at who is asking, so the node it is handed never matters
    private final Node node = mock(Node.class);

    @Test
    void hasAStableName()
    {
        assertEquals("permanent-deletion", this.veto.getName());
    }

    @Test
    void allowsEverythingWhileTheBanIsOff() throws Exception
    {
        this.configure(false);

        assertNull(this.veto.veto(this.node, DeletionMode.PERMANENT, session(ALICE)));
    }

    // Archiving and purging destroy nothing that the archive was holding for recovery, so the ban leaves them alone
    @Test
    void leavesEveryOtherKindOfDeletionAlone() throws Exception
    {
        this.configure(true);
        final Session requester = session(ALICE);

        assertNull(this.veto.veto(this.node, DeletionMode.ARCHIVE, requester));
        assertNull(this.veto.veto(this.node, DeletionMode.PURGE, requester));
    }

    // getUserID() may return null, and the allowlist is an unmodifiable set, which throws rather than
    // answering false when asked whether it contains null
    @Test
    void refusesASessionWithNoUserIdWithoutFailing() throws Exception
    {
        this.configure(true, ALICE);

        assertNotNull(this.veto.veto(this.node, DeletionMode.PERMANENT, session(null)));
    }

    @Test
    void refusesEverybodyWhenNobodyIsExempt() throws Exception
    {
        this.configure(true);

        final String reason = this.veto.veto(this.node, DeletionMode.PERMANENT, session(ALICE));

        // The refusal points at the way out rather than naming who is exempt
        assertEquals("Permanently deleting resources is not permitted here; delete it to the archive instead",
            reason);
    }

    @Test
    void exemptsAUserNamedByIdentity() throws Exception
    {
        this.configure(true, ALICE);

        assertNull(this.veto.veto(this.node, DeletionMode.PERMANENT, session(ALICE)));
    }

    @Test
    void refusesAUserWhoIsNotNamed() throws Exception
    {
        this.configure(true, ALICE);

        assertNotNull(this.veto.veto(this.node, DeletionMode.PERMANENT, jackrabbitSession("bob")));
    }

    // Group membership arrives as a bound principal, which is what makes an identity provider's roles usable here
    // even though they have no local group node behind them
    @Test
    void exemptsAUserThroughAPrincipalTheSessionIsBoundTo() throws Exception
    {
        this.configure(true, REVIEWERS);

        assertNull(this.veto.veto(this.node, DeletionMode.PERMANENT,
            jackrabbitSession("bob", principal(REVIEWERS))));
    }

    @Test
    void refusesAUserWhosePrincipalsAreAllUnnamed() throws Exception
    {
        this.configure(true, REVIEWERS);

        assertNotNull(this.veto.veto(this.node, DeletionMode.PERMANENT,
            jackrabbitSession("bob", principal("submitters"))));
    }

    // Fail closed: a session whose principals cannot be read is exempt from nothing
    @Test
    void refusesASessionWhosePrincipalsCannotBeRead() throws Exception
    {
        this.configure(true, REVIEWERS);

        assertNotNull(this.veto.veto(this.node, DeletionMode.PERMANENT, session("bob")));
    }

    @Test
    void ignoresBlankEntriesInTheAllowlist() throws Exception
    {
        this.configure(true, "", "  ");

        assertNotNull(this.veto.veto(this.node, DeletionMode.PERMANENT, session("")));
    }

    private static Principal principal(final String name)
    {
        return () -> name;
    }

    private static Session session(final String userId)
    {
        final Session session = mock(Session.class);
        when(session.getUserID()).thenReturn(userId);
        return session;
    }

    private static JackrabbitSession jackrabbitSession(final String userId, final Principal... principals)
        throws Exception
    {
        final JackrabbitSession session = mock(JackrabbitSession.class);
        when(session.getUserID()).thenReturn(userId);
        when(session.getBoundPrincipals()).thenReturn(Set.of(principals));
        return session;
    }

    private void configure(final boolean prevent, final String... allowed)
    {
        final PermanentDeletionConfiguration config = mock(PermanentDeletionConfiguration.class);
        when(config.preventPermanentDeletion()).thenReturn(prevent);
        when(config.allowedPrincipals()).thenReturn(allowed);
        this.veto.activate(config);
    }
}
