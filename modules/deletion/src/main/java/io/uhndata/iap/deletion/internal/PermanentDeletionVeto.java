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
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;

/**
 * Reserves permanent deletion — the one deletion that leaves nothing in the archive to restore — to a configured
 * set of principals, or bans it outright.
 *
 * <p>
 * A veto can only refuse, never permit, so the allowlist is an exemption from the ban rather than a grant of the
 * right to delete: an exempt user still needs the same access rights as anybody else, checked separately before any
 * guard runs. With the ban switched off the list means nothing at all.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = PermanentDeletionConfiguration.class)
public class PermanentDeletionVeto implements DeletionVeto
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PermanentDeletionVeto.class);

    private volatile boolean preventPermanentDeletion;

    private volatile Set<String> allowedPrincipals = Set.of();

    @Activate
    protected void activate(final PermanentDeletionConfiguration config)
    {
        this.preventPermanentDeletion = config.preventPermanentDeletion();
        this.allowedPrincipals = Arrays.stream(config.allowedPrincipals())
            .filter(principal -> principal != null && !principal.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String getName()
    {
        return "permanent-deletion";
    }

    @Override
    public String veto(final Node node, final DeletionMode mode, final Session requester)
        throws RepositoryException
    {
        if (!this.preventPermanentDeletion || mode != DeletionMode.PERMANENT) {
            return null;
        }
        if (this.isExempt(requester)) {
            return null;
        }
        // Naming who is exempt would tell an unauthorized caller how the policy is configured; saying what to do
        // instead is both safer and more useful, since archiving remains open to them.
        return "Permanently deleting resources is not permitted here; delete it to the archive instead";
    }

    /**
     * Whether the requester is named in the allowlist, either by user id or through any principal the session is
     * bound to.
     */
    private boolean isExempt(final Session requester) throws RepositoryException
    {
        if (this.allowedPrincipals.isEmpty()) {
            return false;
        }
        if (this.allowedPrincipals.contains(requester.getUserID())) {
            return true;
        }
        // Membership is read from the principals the requesting session is <em>bound to</em>, not from group nodes
        // found through {@code UserManager}. The two differ in exactly the deployment this is meant for: with
        // {@code user.dynamicMembership} enabled, an identity provider's roles reach the repository as principals with
        // no local group node behind them, so a membership lookup would report that a Keycloak role's members belong to
        // nothing. Bound principals cover local groups and provider-supplied roles alike, which is what lets the two
        // vocabularies be used interchangeably in the configuration.
        if (!(requester instanceof JackrabbitSession jackrabbitSession)) {
            // Fail closed: without the Jackrabbit API there is no way to learn what this session acts as
            LOGGER.warn("Cannot read the principals of a {}, so the permanent deletion policy exempts nobody",
                requester.getClass().getName());
            return false;
        }
        return jackrabbitSession.getBoundPrincipals().stream()
            .map(Principal::getName)
            .anyMatch(this.allowedPrincipals::contains);
    }
}
