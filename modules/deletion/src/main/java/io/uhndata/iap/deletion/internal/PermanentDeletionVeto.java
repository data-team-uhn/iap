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
import java.util.EnumSet;
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
 * Reserves irreversible deletion to a configured set of principals, or bans it outright.
 *
 * <p>
 * Two modes destroy data with nothing left to restore, and the ban covers both: {@link DeletionMode#PERMANENT},
 * which never reaches the archive, and {@link DeletionMode#PURGE}, which removes what is already in it. Guarding
 * only the first would leave the ban defeatable in two ordinary steps — archive, then purge.
 * </p>
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

    /** The deletions that leave nothing behind to restore. */
    private static final Set<DeletionMode> IRREVERSIBLE = EnumSet.of(DeletionMode.PERMANENT, DeletionMode.PURGE);

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
        if (!this.preventPermanentDeletion || !IRREVERSIBLE.contains(mode)) {
            return null;
        }
        if (this.isExempt(requester)) {
            return null;
        }
        // Naming who is exempt would tell an unauthorized caller how the policy is configured; saying where the
        // resource ends up instead is both safer and more useful. Which reassurance is true depends on the mode: an
        // ordinary deletion is still open to them, but a resource that is already archived simply stays there.
        return mode == DeletionMode.PURGE
            ? "Destroying archived resources is not permitted here; this one stays in the archive"
            : "Permanently deleting resources is not permitted here; delete it to the archive instead";
    }

    @Override
    public boolean judgesWholeOperation()
    {
        // The ban is about the operation, so the answer is the same for every impacted resource; asking per resource
        // would report one identical objection per node of the subtree.
        return true;
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
        // getUserID() is permitted to be null, and an unmodifiable set throws rather than answering false for it
        final String userId = requester.getUserID();
        if (userId != null && this.allowedPrincipals.contains(userId)) {
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
