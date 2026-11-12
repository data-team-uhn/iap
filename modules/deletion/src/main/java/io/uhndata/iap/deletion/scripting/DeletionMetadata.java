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
package io.uhndata.iap.deletion.scripting;

import java.net.URI;
import java.net.URISyntaxException;

import javax.annotation.PostConstruct;

import jakarta.servlet.RequestDispatcher;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTL helper for the 404 page, so that the page can say a missing resource was deleted. It asks
 * {@link DeletedPathDisclosure} about the path the request was for. The error handler's HTL file uses it as:
 *
 * {@snippet lang="html" :
 * <sly data-sly-use.deletion="io.uhndata.iap.deletion.scripting.DeletionMetadata"></sly>
 * }
 *
 * <p>
 * Each getter is {@code null} when there is nothing to say, and HTL then leaves its attribute out of the markup.
 * </p>
 *
 * <p>
 * This is a Sling Model, not a {@code Use} POJO. Sling Models injects its {@code @OSGiService} field on behalf of
 * this bundle. A POJO asks through the script's {@code sling} binding, on behalf of
 * {@code org.apache.sling.scripting.core}, which is wired to neither this package nor this bundle's service user.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = SlingJakartaHttpServletRequest.class,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DeletionMetadata
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DeletionMetadata.class);

    @Self
    private SlingJakartaHttpServletRequest request;

    @OSGiService
    private DeletedPathDisclosure disclosure;

    private String deletedAt;

    private String deletedBy;

    private String entryUrl;

    @PostConstruct
    protected void init()
    {
        final String path = requestedPath(this.request);
        if (path == null) {
            return;
        }
        if (this.disclosure == null) {
            // The page still renders a plain 404
            LOGGER.warn("Cannot look up whether {} was deleted: the deletion module is not available", path);
            return;
        }
        final DeletedPathDisclosure.Disclosure told = this.disclosure.describe(this.request, path);
        if (told != null) {
            this.deletedAt = told.deletedAt();
            this.deletedBy = told.deletedBy();
            this.entryUrl = told.entryUrl();
        }
    }

    /**
     * When the requested path was deleted. A page with no date treats the path as one that never existed.
     *
     * @return an ISO-8601 timestamp; {@code null} if no deletion took the path away, or the reader may not know
     */
    @Nullable
    public String getDeletedAt()
    {
        return this.deletedAt;
    }

    /**
     * Who deleted the requested path.
     *
     * @return the user id of the user who requested the deletion; {@code null} unless the reader can read the
     *         archive entry
     */
    @Nullable
    public String getDeletedBy()
    {
        return this.deletedBy;
    }

    /**
     * Where the reader can open the archive entry.
     *
     * @return the console route of the entry's page; {@code null} unless the reader can read the entry
     */
    @Nullable
    public String getEntryUrl()
    {
        return this.entryUrl;
    }

    /**
     * The path this request was for: the request URI the error dispatch recorded, decoded. It keeps the selectors
     * and the extension the reader's link had.
     *
     * <p>
     * {@link URI#getPath()} decodes it, not {@code URLDecoder}. That one decodes forms, and reads {@code +} as a
     * space, but a browser never escapes a {@code +} in a path.
     * </p>
     *
     * @param request the request the error handler is running on
     * @return the absolute path; {@code null} if the URI is not an absolute path, or cannot be parsed
     */
    @Nullable
    private static String requestedPath(final SlingJakartaHttpServletRequest request)
    {
        final Object recorded = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Without an error dispatch, the script was requested directly and the request's own URI failed
        final String uri = recorded instanceof String ? (String) recorded : request.getRequestURI();
        if (uri == null || !uri.startsWith("/")) {
            return null;
        }
        try {
            return new URI(uri).getPath();
        } catch (final URISyntaxException e) {
            // Nothing can have been archived at a path that does not parse
            return null;
        }
    }
}
