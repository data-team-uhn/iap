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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Issues and checks session cookies with the configured limits. A session's idle deadline is the earlier of a full
 * idle stretch after now and the latest the session may last, so a renewal never carries it past that.
 *
 * <p>Without the signing key, which a deployment fault can withhold, no session can be checked or begun: every
 * cookie is refused, and beginning a session throws. Signing in then fails, rather than letting a session last
 * without a limit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SessionCookies.class)
@Designate(ocd = SessionLimitsConfiguration.class)
public class SessionCookiesImpl implements SessionCookies
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCookiesImpl.class);

    private final Clock clock;

    private final Duration idle;

    private final Duration maximum;

    private final SignedSessions signer;

    /**
     * Loads the signing key and reads the limits.
     *
     * @param resolverFactory supplies the service user that reads and creates the signing key
     * @param config the limits
     */
    @Activate
    public SessionCookiesImpl(@Reference final ResourceResolverFactory resolverFactory,
        final SessionLimitsConfiguration config)
    {
        this(Clock.systemUTC(), config, loadSigner(resolverFactory));
    }

    /**
     * Uses the given clock and signer.
     *
     * @param clock tells the time
     * @param config the limits
     * @param signer signs and checks cookie values; {@code null} when the signing key could not be loaded
     */
    SessionCookiesImpl(final Clock clock, final SessionLimitsConfiguration config, final SignedSessions signer)
    {
        this.clock = clock;
        this.idle = Duration.ofMinutes(Math.max(1, config.idleMinutes()));
        this.maximum = Duration.ofHours(Math.max(1, config.maximumHours()));
        this.signer = signer;
    }

    @Override
    public String begin(final String userId, final String kind, final boolean secure)
    {
        final Instant now = this.clock.instant();
        return this.store(new Session(userId, kind, now, this.deadline(now, now)), now, secure);
    }

    @Override
    public Session read(final String cookieValue)
    {
        if (cookieValue == null || this.signer == null) {
            return null;
        }
        final Session session = this.signer.decode(cookieValue);
        final Instant now = this.clock.instant();
        if (session == null || !now.isBefore(session.idleDeadline())
            || !now.isBefore(session.signedIn().plus(this.maximum))) {
            return null;
        }
        return session;
    }

    @Override
    public String renew(final Session session, final boolean secure)
    {
        final Instant now = this.clock.instant();
        return this.store(new Session(session.userId(), session.kind(), session.signedIn(),
            this.deadline(session.signedIn(), now)), now, secure);
    }

    @Override
    public String end(final boolean secure)
    {
        return header("", 0, secure);
    }

    /**
     * When a session renewed now ends without another request: a full idle stretch away, but never after the latest
     * it may last.
     *
     * @param signedIn when the session began
     * @param now the time of the renewal
     * @return the idle deadline
     */
    private Instant deadline(final Instant signedIn, final Instant now)
    {
        final Instant idleEnd = now.plus(this.idle);
        final Instant latest = signedIn.plus(this.maximum);
        return idleEnd.isBefore(latest) ? idleEnd : latest;
    }

    private String store(final Session session, final Instant now, final boolean secure)
    {
        if (this.signer == null) {
            throw new IllegalStateException("No session can be begun: the session signing key could not be loaded");
        }
        return header(this.signer.encode(session), Duration.between(now, session.idleDeadline()).getSeconds(),
            secure);
    }

    private static String header(final String value, final long maxAgeSeconds, final boolean secure)
    {
        return COOKIE_NAME + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax"
            + (secure ? "; Secure" : "");
    }

    private static SignedSessions loadSigner(final ResourceResolverFactory resolverFactory)
    {
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(null)) {
            return new SignedSessions(new SessionKeyStore(resolver, new SecureRandom()).readOrCreate());
        } catch (final LoginException | PersistenceException | GeneralSecurityException | IllegalStateException e) {
            LOGGER.error("Sessions cannot be signed, so nobody can sign in: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(SessionCookiesImpl.class, "loadSigningKey")
                .about(SessionKeyStore.KEY_PATH));
            return null;
        }
    }
}
