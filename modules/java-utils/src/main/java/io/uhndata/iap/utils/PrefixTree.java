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
package io.uhndata.iap.utils;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.jetbrains.annotations.NotNull;

/**
 * Spreads a large, open-ended set of sibling nodes over a tree of buckets named after the first characters of each
 * node's own name, {@code <root>/<xx>/<yy>/<zz>/<name>}, the way Oak files version histories under
 * {@code /jcr:system}. Piling every node of a kind under one parent produces a node that repository browsers
 * cannot open and that the repository itself starts warning about; {@value #LEVELS} levels of
 * {@value #SEGMENT_LENGTH} characters keep every parent below 256 children until there are millions of nodes.
 *
 * <p>
 * Three properties of this layout are part of the contract, not implementation details:
 * </p>
 * <ul>
 * <li><b>Names must be uniformly distributed</b>, i.e. a UUID or a hash. Filing nodes under a name people chose,
 * or under a sequential identifier, piles them into a handful of buckets and rebuilds the very flat list this
 * exists to avoid.</li>
 * <li><b>Each bucket is saved as it is created</b>, so {@link #bucketFor} must be called <em>before</em> its
 * caller has any other pending change: recovering from a lost race calls {@code refresh(false)}, which would
 * otherwise discard that work. Buckets are inert, so one left behind by an operation that goes on to fail costs
 * nothing and gets reused.</li>
 * <li><b>Buckets are never removed</b>, including when the last node in one goes away.</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PrefixTree
{
    /** The number of bucket levels between the root and a node filed in the tree: {@value}. */
    public static final int LEVELS = 3;

    /** How many characters of a node's name each bucket level is named after: {@value}. */
    public static final int SEGMENT_LENGTH = 2;

    /** The length of the shortest name that can be filed in a prefix tree: {@value}. */
    public static final int MINIMUM_NAME_LENGTH = LEVELS * SEGMENT_LENGTH;

    private PrefixTree()
    {
        // Prevent instantiation of a utility class
    }

    /**
     * Find, creating them as needed, the buckets that a node belongs in. The node itself is not created: the
     * caller adds it to the returned parent, under the same name, with whatever type and properties it needs.
     *
     * @param root the top of the prefix tree, e.g. the parent that would otherwise hold every node directly
     * @param name the name of the node being filed, at least {@value #MINIMUM_NAME_LENGTH} uniformly
     *            distributed characters
     * @param bucketNodeType the primary type to create missing buckets with; the root's node type must allow it
     *            as a child, and so must the type itself, since buckets nest
     * @return the node that the named node must be added to
     * @throws IllegalArgumentException if the name is too short to file
     * @throws RepositoryException if the buckets cannot be read or created
     */
    @NotNull
    public static Node bucketFor(@NotNull final Node root, @NotNull final String name,
        @NotNull final String bucketNodeType) throws RepositoryException
    {
        checkName(name);
        Node bucket = root;
        for (int level = 0; level < LEVELS; ++level) {
            bucket = getOrCreateBucket(bucket, segment(name, level), bucketNodeType);
        }
        return bucket;
    }

    /**
     * The path a node filed in a prefix tree has, computed without touching the repository. Useful for looking a
     * node up by name, and for queries and tests that would otherwise hardcode the layout.
     *
     * @param rootPath the absolute path of the top of the prefix tree
     * @param name the name of the node, at least {@value #MINIMUM_NAME_LENGTH} uniformly distributed characters
     * @return an absolute path, whether or not anything exists there
     * @throws IllegalArgumentException if the name is too short to file
     */
    @NotNull
    public static String pathFor(@NotNull final String rootPath, @NotNull final String name)
    {
        checkName(name);
        final String prefix = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        return IntStream.range(0, LEVELS)
            .mapToObj(level -> segment(name, level))
            .collect(Collectors.joining("/", prefix, "/" + name));
    }

    /**
     * One bucket, created if this is the first node to land in it. A new bucket is saved on its own so that two
     * sessions racing into the same bucket cannot lose a commit: the loser discards its own unsaved bucket and
     * adopts the winner's.
     */
    private static Node getOrCreateBucket(final Node parent, final String name, final String nodeType)
        throws RepositoryException
    {
        if (parent.hasNode(name)) {
            return parent.getNode(name);
        }
        try {
            final Node bucket = parent.addNode(name, nodeType);
            parent.getSession().save();
            return bucket;
        } catch (final InvalidItemStateException | ItemExistsException e) {
            // Somebody else got there first; drop our own attempt and take theirs
            parent.getSession().refresh(false);
            return parent.getNode(name);
        }
    }

    private static String segment(final String name, final int level)
    {
        return name.substring(level * SEGMENT_LENGTH, (level + 1) * SEGMENT_LENGTH);
    }

    private static void checkName(final String name)
    {
        if (name.length() < MINIMUM_NAME_LENGTH) {
            throw new IllegalArgumentException("A name filed in a prefix tree needs at least "
                + MINIMUM_NAME_LENGTH + " characters, received \"" + name + "\"");
        }
    }
}
