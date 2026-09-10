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
 * A HTL helper for the 404 page: it answers "was something that used to live here deleted?" about the path the
 * request was for, so that a dead link can say the resource was deleted rather than that it never existed. To use
 * it, place the following in the error handler HTL file:
 *
 * <p>
 * <code>
 * &lt;sly data-sly-use.deletion="io.uhndata.iap.deletion.scripting.DeletionMetadata"&gt;&lt;/sly&gt;
 * </code>
 * </p>
 *
 * <p>
 * The page renders the answer into the markup it was already sending, so a reader who followed a dead link is told
 * what became of it in the first response rather than after a second round trip. Every getter here is {@code null}
 * when there is nothing to say, which is what HTL needs to leave the attribute carrying it out of the markup
 * altogether.
 * </p>
 *
 * <p>
 * A <b>Sling Model</b> rather than a {@code Use} POJO, and that is not a stylistic choice: a model's
 * {@code @OSGiService} field is injected on behalf of the bundle declaring the model, whereas a POJO reaching for
 * the same service through its script's {@code sling} binding asks on behalf of
 * {@code org.apache.sling.scripting.core} — which is wired to neither this package nor the service user this
 * lookup needs. HTL is happy either way: its {@code JavaUseProvider} adapts a model from the request before it
 * ever considers the {@code Use} interface.
 * </p>
 *
 * <p>
 * The reading of the archive, and the decision about how much of it this reader may be told, belong to
 * {@link DeletedPathDisclosure}. What is left here is the request: which path was asked for, and how to say the
 * answer.
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
            // An error page is the worst place to raise an error of its own, so a platform whose deletion module
            // is not running still gets a plain, working 404
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
     * When the requested path was deleted, and the signal that it was deleted at all.
     *
     * @return an ISO-8601 timestamp, or {@code null} if nothing archived covers the requested path
     */
    @Nullable
    public String getDeletedAt()
    {
        return this.deletedAt;
    }

    /**
     * Who deleted it, for a reader allowed to know.
     *
     * @return the user who requested the deletion, or {@code null} if the reader cannot read the archive entry
     */
    @Nullable
    public String getDeletedBy()
    {
        return this.deletedBy;
    }

    /**
     * Where to look at the archive entry, for a reader allowed to open it.
     *
     * @return the console route to the entry, or {@code null} if the reader cannot read it
     */
    @Nullable
    public String getEntryUrl()
    {
        return this.entryUrl;
    }

    /**
     * The repository path this request was for.
     *
     * <p>
     * An error handler runs on the request that failed, so the path is the one the error dispatch recorded rather
     * than anything the page has to be told: no query parameter carries it, and so nothing has to encode it to get
     * here. What arrives is a request URI — percent-encoded, and still carrying whatever selectors and extension
     * the reader's link had on it, which is what {@code DeletedPathLookup} peels apart.
     * </p>
     *
     * <p>
     * Undoing the encoding is {@link URI#getPath()}'s job, not {@code URLDecoder}'s: that one is the form decoder,
     * and it reads a literal {@code +} in a path as a space — measured, {@code /a+b} comes back as {@code /a b}. A
     * path a browser produced never escapes {@code +}, so the two disagree on exactly the character a reader
     * cannot escape for us.
     * </p>
     *
     * @param request the request the error handler is running on
     * @return the absolute repository path, or {@code null} if the dispatch recorded no usable one
     */
    @Nullable
    private static String requestedPath(final SlingJakartaHttpServletRequest request)
    {
        final Object recorded = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Without an error dispatch — the script rendered directly — the request's own URI is the one that failed
        final String uri = recorded instanceof String ? (String) recorded : request.getRequestURI();
        if (uri == null || !uri.startsWith("/")) {
            return null;
        }
        try {
            return new URI(uri).getPath();
        } catch (final URISyntaxException e) {
            // Not a path anything could ever have been archived at, so there is nothing to look up and nothing
            // worth recording
            return null;
        }
    }
}
