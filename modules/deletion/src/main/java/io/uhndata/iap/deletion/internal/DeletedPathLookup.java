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

import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

import io.uhndata.iap.deletion.api.DeletionService;

/**
 * Answers "was something that used to live here deleted?" for one requested path.
 *
 * <p>
 * The question comes from the 404 page, which has a request URI rather than a resource path. The resource never
 * resolved, so nothing split the selectors and extension off it. {@link #candidates} reproduces that split the way
 * resource resolution does, peeling one dot-suffix at a time off the last segment before dropping the segment
 * itself. The lookup asks about every result at once. Climbing matters as much as peeling. A deletion archives a
 * whole subtree under one recorded path, so a request for something inside it matches an ancestor and nothing
 * else.
 * </p>
 *
 * <p>
 * A path can have been archived more than once: deleted, restored, deleted again, or simply reused. Matches are
 * ranked rather than assumed unique. The most specific recorded path wins, and between two deletions of the same
 * path the most recent one does. That is the one that made the link dead.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletedPathLookup
{
    /**
     * How many paths one request may ask about. Deeper than any real content path with selectors on it, and a
     * bound on what a crafted URI can make the repository do. A path
     * deeper than this keeps its most specific candidates and loses its topmost ancestors, which are the least
     * likely to be the deleted subtree.
     */
    static final int MAX_CANDIDATES = 24;

    private DeletedPathLookup()
    {
        // Utility class
    }

    /**
     * Find the archived item covering a requested path.
     *
     * @param serviceSession a session that can read the archive, since the requester's cannot
     * @param requestedPath the path from the request
     * @return the deletion that took the path away, or empty if none did
     * @throws RepositoryException if the archive cannot be queried
     */
    public static Optional<Archived> find(final Session serviceSession, final String requestedPath)
        throws RepositoryException
    {
        final List<String> candidates = candidates(requestedPath);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        final RowIterator rows = serviceSession.getWorkspace().getQueryManager()
            .createQuery(statement(candidates), Query.JCR_SQL2).execute().getRows();
        Archived best = null;
        while (rows.hasNext()) {
            final Archived found = describe(rows.nextRow().getNode());
            if (found != null && found.isBetterThan(best)) {
                best = found;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The paths a request URI could have been addressing, most specific first.
     *
     * @param requestedPath the path from the request, possibly carrying selectors and an extension
     * @return the paths to look for, in the order they should be preferred; empty if the argument is not an
     *         absolute path, or is the root
     */
    static List<String> candidates(final String requestedPath)
    {
        if (requestedPath == null || !requestedPath.startsWith("/")) {
            return List.of();
        }
        // Every step strictly shortens the path, so the sequence terminates and cannot repeat itself
        return Stream.iterate(requestedPath.replaceAll("/+$", ""), path -> path.length() > 1,
            DeletedPathLookup::shorten).limit(MAX_CANDIDATES).toList();
    }

    /**
     * The statement asking for every archived item recorded at any of the candidate paths. A chain of equalities
     * rather than a {@code LIKE} over the subtree. It stays on the {@code originalPath} property index, and a
     * wildcard character in a path cannot widen the match.
     *
     * @param candidates the paths to ask about
     * @return a JCR-SQL2 statement
     */
    static String statement(final List<String> candidates)
    {
        return "SELECT item.* FROM [" + DeletionService.ITEM_NODETYPE + "] AS item WHERE ISDESCENDANTNODE(item, '"
            + ArchiveQuery.literal(DeletionService.ARCHIVE_PATH) + "') AND ("
            + candidates.stream()
                .map(candidate -> "item.[" + DeletionService.ORIGINAL_PATH_PROPERTY + "] = '"
                    + ArchiveQuery.literal(candidate) + "'")
                .collect(Collectors.joining(" OR "))
            + ")";
    }

    /**
     * One path shortened by one step, the way resource resolution would have. A trailing dot-suffix comes off
     * before the segment carrying it does, so {@code /a/b.sel.html} yields {@code /a/b.sel} rather than
     * {@code /a}. A dot opening a segment is part of the name, and is never peeled.
     *
     * @param path the path to shorten, without a trailing slash
     * @return the next shorter path, empty once there is nothing left
     */
    private static String shorten(final String path)
    {
        final int lastSlash = path.lastIndexOf('/');
        final int lastDot = path.lastIndexOf('.');
        return lastDot > lastSlash + 1 ? path.substring(0, lastDot) : path.substring(0, lastSlash);
    }

    /**
     * Describe one matching archived item, or {@code null} if it is not the record of a deletion at all. The
     * archive can hold an archived wrapper, dragged along by somebody else's deletion, and that wrapper's recorded
     * path is history rather than a resource anybody is looking for. Everything else read here is guaranteed by
     * the node types and by the equality the query matched on, so a missing one is a broken repository.
     */
    private static Archived describe(final Node item) throws RepositoryException
    {
        final Node entry = item.getParent();
        if (!entry.isNodeType(DeletionService.ENTRY_NODETYPE)) {
            return null;
        }
        final Calendar created = entry.getProperty("jcr:created").getDate();
        return new Archived(item.getProperty(DeletionService.ORIGINAL_PATH_PROPERTY).getString(), entry.getPath(),
            entry.getName(), entry.getProperty(DeletionService.DELETED_BY_PROPERTY).getString(),
            created.toInstant().atZone(created.getTimeZone().toZoneId()));
    }

    /**
     * One deletion that took a requested path away.
     *
     * @param originalPath the path the archived subtree was moved from: the requested path, or an ancestor of it
     * @param entryPath where the archive entry is stored, inside the prefix tree
     * @param entryName the entry's name, which is how it is addressed without the prefix tree
     * @param deletedBy the user who requested the deletion
     * @param deletedAt when the entry was created, in the timezone the repository recorded it in
     * @version $Id$
     * @since 0.1.0
     */
    public record Archived(String originalPath, String entryPath, String entryName, String deletedBy,
        ZonedDateTime deletedAt)
    {
        /**
         * Whether this match should be preferred over another. Length stands in for specificity: every candidate
         * is strictly shorter than the one before it, so the longer recorded path is always the more specific one.
         *
         * <p>
         * The name settles a tie. {@code jcr:created} has millisecond resolution, so two deletions of one path
         * can carry the same instant, and nothing then says which came second. Answering by name is arbitrary and
         * stable. Without it the winner is whichever the query returned first, and one question gets two answers.
         * </p>
         *
         * @param other the match to compare against, {@code null} when there is none yet
         * @return {@code true} if this match is the better answer
         */
        boolean isBetterThan(final Archived other)
        {
            if (other == null) {
                return true;
            }
            if (this.originalPath.length() != other.originalPath.length()) {
                return this.originalPath.length() > other.originalPath.length();
            }
            if (!this.deletedAt.isEqual(other.deletedAt)) {
                return this.deletedAt.isAfter(other.deletedAt);
            }
            return this.entryName.compareTo(other.entryName) > 0;
        }
    }
}
