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
package io.uhndata.iap.auth.sessions.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ModifiableValueMapDecorator;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionCookiesImpl}.
 *
 * @version $Id$
 */
class SessionCookiesImplTest
{
    private static final Instant SIGN_IN = Instant.parse("2026-11-26T09:00:00Z");

    private final MovingClock clock = new MovingClock(SIGN_IN);

    private final SessionCookiesImpl sessions = new SessionCookiesImpl(this.clock, limits(30, 8), signer());

    @Test
    void beginsASessionThatLastsTheIdleLimit()
    {
        final String header = this.sessions.begin("alice", SessionCookies.FORM_KIND, false);

        assertTrue(header.startsWith("iap.session="));
        assertTrue(header.endsWith("; Path=/; Max-Age=1800; HttpOnly; SameSite=Lax"));
        assertEquals(new Session("alice", "form", SIGN_IN, SIGN_IN.plus(Duration.ofMinutes(30))),
            this.sessions.read(valueOf(header)));
    }

    @Test
    void sendsTheCookieOverHttpsOnlyWhenTheRequestCameThatWay()
    {
        assertTrue(this.sessions.begin("alice", SessionCookies.FORM_KIND, true).endsWith("; Secure"));
        assertTrue(this.sessions.end(true).endsWith("; Secure"));
    }

    @Test
    void endsASessionLeftIdle()
    {
        final String value = valueOf(this.sessions.begin("alice", SessionCookies.FORM_KIND, false));

        this.clock.advance(Duration.ofMinutes(29));
        assertNotNull(this.sessions.read(value));
        this.clock.advance(Duration.ofMinutes(1));
        assertNull(this.sessions.read(value));
    }

    @Test
    void renewingGivesAnotherFullIdleStretch()
    {
        final String value = valueOf(this.sessions.begin("alice", SessionCookies.FORM_KIND, false));
        this.clock.advance(Duration.ofMinutes(20));

        final String renewed = valueOf(this.sessions.renew(this.sessions.read(value), false));

        this.clock.advance(Duration.ofMinutes(29));
        assertNull(this.sessions.read(value));
        assertEquals(SIGN_IN, this.sessions.read(renewed).signedIn());
    }

    @Test
    void noRenewalCarriesASessionPastTheMaximum()
    {
        String value = valueOf(this.sessions.begin("alice", SessionCookies.FORM_KIND, false));
        for (int i = 0; i < 31; i++) {
            this.clock.advance(Duration.ofMinutes(15));
            value = valueOf(this.sessions.renew(this.sessions.read(value), false));
        }

        // Seven hours and three quarters in: the renewal reaches only the end of the eighth hour
        final String header = this.sessions.renew(this.sessions.read(value), false);
        assertTrue(header.contains("; Max-Age=900;"));
        this.clock.advance(Duration.ofMinutes(15));
        assertNull(this.sessions.read(valueOf(header)));
    }

    @Test
    void refusesWhatIsNotASession()
    {
        assertNull(this.sessions.read(null));
        assertNull(this.sessions.read("forged"));
    }

    @Test
    void refusesASessionAShorterMaximumHasEnded()
    {
        final String value = valueOf(this.sessions.begin("alice", SessionCookies.FORM_KIND, false));
        this.clock.advance(Duration.ofMinutes(20));
        final String once = valueOf(this.sessions.renew(this.sessions.read(value), false));
        this.clock.advance(Duration.ofMinutes(25));
        // Renewed under eight hours at 09:45, to 10:15
        final String renewed = valueOf(this.sessions.renew(this.sessions.read(once), false));
        final SessionCookiesImpl stricter = new SessionCookiesImpl(this.clock, limits(30, 1), signer());
        assertNotNull(stricter.read(renewed));

        this.clock.advance(Duration.ofMinutes(15));

        // Idle until 10:15 still, but a one-hour maximum ended at 10:00
        assertNotNull(this.sessions.read(renewed));
        assertNull(stricter.read(renewed));
    }

    @Test
    void endingRemovesTheCookie()
    {
        assertEquals("iap.session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax", this.sessions.end(false));
    }

    @Test
    void sharesTheKeyWithEveryInstanceUsingTheRepository() throws LoginException
    {
        final Map<String, Object> keyNode = new HashMap<>();
        final SessionCookiesImpl first = new SessionCookiesImpl(factoryOver(keyNode), limits(30, 8));
        final SessionCookiesImpl second = new SessionCookiesImpl(factoryOver(keyNode), limits(30, 8));

        final String value = valueOf(first.begin("alice", SessionCookies.FORM_KIND, false));

        assertEquals("alice", second.read(value).userId());
    }

    @Test
    void refusesEverySessionWithoutAKey() throws LoginException
    {
        final ResourceResolverFactory factory = mock(ResourceResolverFactory.class);
        when(factory.getServiceResourceResolver(null)).thenThrow(new LoginException("no service user"));
        final SessionCookiesImpl keyless = new SessionCookiesImpl(factory, limits(30, 8));

        assertNull(keyless.read(valueOf(this.sessions.begin("alice", SessionCookies.FORM_KIND, false))));
        assertThrows(IllegalStateException.class, () -> keyless.begin("alice", SessionCookies.FORM_KIND, false));
    }

    private static SignedSessions signer()
    {
        try {
            return new SignedSessions("the key".getBytes(StandardCharsets.UTF_8));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SessionLimitsConfiguration limits(final int idleMinutes, final int maximumHours)
    {
        final SessionLimitsConfiguration config = mock(SessionLimitsConfiguration.class);
        when(config.idleMinutes()).thenReturn(idleMinutes);
        when(config.maximumHours()).thenReturn(maximumHours);
        return config;
    }

    // A factory whose service resolver shows the key node, its properties kept in the given map
    private static ResourceResolverFactory factoryOver(final Map<String, Object> properties) throws LoginException
    {
        final Resource node = mock(Resource.class);
        when(node.getValueMap()).thenAnswer(invocation -> new ValueMapDecorator(properties));
        when(node.adaptTo(ModifiableValueMap.class))
            .thenAnswer(invocation -> new ModifiableValueMapDecorator(properties));
        final ResourceResolver resolver = mock(ResourceResolver.class);
        when(resolver.getResource(SessionKeyStore.KEY_PATH)).thenReturn(node);
        final ResourceResolverFactory factory = mock(ResourceResolverFactory.class);
        when(factory.getServiceResourceResolver(null)).thenReturn(resolver);
        return factory;
    }

    private static String valueOf(final String header)
    {
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    /** A clock that stands still until a test moves it on. */
    private static final class MovingClock extends Clock
    {
        private Instant now;

        MovingClock(final Instant start)
        {
            this.now = start;
        }

        void advance(final Duration duration)
        {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return this.now;
        }
    }
}
