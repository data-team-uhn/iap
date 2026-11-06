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
 * When switched on, bans deletion for good to all but the configured users and principals. The ban covers both
 * {@link DeletionMode#PERMANENT} and {@link DeletionMode#PURGE}. Banning only the first would let anybody archive,
 * then purge.
 *
 * <p>
 * The allowlist exempts from the ban and grants nothing. An exempt user still needs the access rights any deletion
 * needs. With the ban off, the list does nothing.
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
    private static final Set<DeletionMode> IRREVERSIBLE_MODES = EnumSet.of(DeletionMode.PERMANENT, DeletionMode.PURGE);

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
        if (!this.preventPermanentDeletion || !IRREVERSIBLE_MODES.contains(mode)) {
            return null;
        }
        if (this.isExempt(requester)) {
            return null;
        }
        // The message says where the resource stays, and never who is exempt
        return mode == DeletionMode.PURGE
            ? "Destroying archived resources is not permitted here; this one stays in the archive"
            : "Permanently deleting resources is not permitted here; delete it to the archive instead";
    }

    @Override
    public boolean judgesWholeOperation()
    {
        // The answer does not depend on the node
        return true;
    }

    /**
     * Whether the allowlist names the requester's user id, or any principal their session is bound to.
     */
    private boolean isExempt(final Session requester) throws RepositoryException
    {
        if (this.allowedPrincipals.isEmpty()) {
            return false;
        }
        // getUserID() can be null, and Set.contains(null) throws on an unmodifiable set
        final String userId = requester.getUserID();
        if (userId != null && this.allowedPrincipals.contains(userId)) {
            return true;
        }
        // Bound principals, not a UserManager membership lookup. With user.dynamicMembership, an identity provider's
        // roles have no local group node, and a lookup finds that their members belong to nothing.
        if (!(requester instanceof JackrabbitSession jackrabbitSession)) {
            // Without the Jackrabbit API, the session's principals cannot be read
            LOGGER.warn("Cannot read the principals of a {}, so the permanent deletion policy exempts nobody",
                requester.getClass().getName());
            return false;
        }
        return jackrabbitSession.getBoundPrincipals().stream()
            .map(Principal::getName)
            .anyMatch(this.allowedPrincipals::contains);
    }
}
