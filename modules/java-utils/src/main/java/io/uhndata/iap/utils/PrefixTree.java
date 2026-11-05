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

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Files a large, open-ended set of nodes under buckets named after the first characters of each node's name, as
 * {@code <root>/<xx>/<yy>/<zz>/<name>}, so that no parent holds them all. With hexadecimal names, such as UUIDs, a
 * bucket holds at most 256 buckets.
 *
 * <ul>
 * <li><b>Names must be evenly spread</b>, such as UUIDs or hashes. Names people chose, or sequential identifiers,
 * crowd into a few buckets.</li>
 * <li><b>{@link #bucketFor} saves the session after each bucket it creates</b>, and on a conflict it discards the
 * session's pending changes. Call it before making any other change in the session. A caller writing inside a
 * transaction it does not own uses {@link #pendingBucketFor} instead.</li>
 * <li><b>Buckets are never removed</b>, even when the last node in one goes away. A bucket that {@link #bucketFor}
 * saved stays even if the operation it was for fails.</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PrefixTree
{
    /** The number of bucket levels between the root and a filed node: {@value}. */
    public static final int BUCKET_LEVELS = 3;

    /** How many characters of a node's name each bucket level is named after: {@value}. */
    public static final int SEGMENT_LENGTH = 2;

    /** The length of the shortest name that can be filed in a prefix tree: {@value}. */
    public static final int MINIMUM_NAME_LENGTH = BUCKET_LEVELS * SEGMENT_LENGTH;

    /**
     * The segment under which a filed node can be addressed by its name alone, as {@code <root>/by-id/<name>}:
     * {@value}. It cannot collide with a bucket, whose name is always {@value #SEGMENT_LENGTH} characters long.
     */
    public static final String ADDRESS_SEGMENT = "by-id";

    private PrefixTree()
    {
    }

    /**
     * Finds the bucket a node belongs in, creating and saving any bucket missing on the way. The node itself is not
     * created: the caller adds it to the returned bucket, under the same name.
     *
     * @param root the top of the prefix tree, such as the parent that would otherwise hold every node directly
     * @param name the name of the node being filed, at least {@value #MINIMUM_NAME_LENGTH} uniformly
     *            distributed characters
     * @param bucketNodeType the primary type to create missing buckets with; the root's node type must allow it
     *            as a child, and so must the type itself, since buckets nest
     * @return the bucket the named node must be added to
     * @throws IllegalArgumentException if the name is too short to file
     * @throws RepositoryException if the buckets cannot be read, created or saved
     */
    @NotNull
    public static Node bucketFor(@NotNull final Node root, @NotNull final String name,
        @NotNull final String bucketNodeType) throws RepositoryException
    {
        checkName(name);
        Node bucket = root;
        for (int level = 0; level < BUCKET_LEVELS; ++level) {
            bucket = getOrCreateBucket(bucket, segment(name, level), bucketNodeType);
        }
        return bucket;
    }

    /**
     * Finds the bucket a node belongs in, adding any bucket missing on the way to the resolver's pending changes.
     * Nothing is saved, so the buckets are committed or reverted with whatever else the caller writes.
     *
     * @param root the top of the prefix tree, such as the parent that would otherwise hold every node directly
     * @param name the name of the node being filed, at least {@value #MINIMUM_NAME_LENGTH} uniformly
     *            distributed characters
     * @param bucketNodeType the primary type to create missing buckets with; the root's node type must allow it
     *            as a child, and so must the type itself, since buckets nest
     * @return the bucket the named node must be added to
     * @throws IllegalArgumentException if the name is too short to file
     * @throws PersistenceException if a missing bucket cannot be added
     */
    @NotNull
    public static Resource pendingBucketFor(@NotNull final Resource root, @NotNull final String name,
        @NotNull final String bucketNodeType) throws PersistenceException
    {
        checkName(name);
        Resource bucket = root;
        for (int level = 0; level < BUCKET_LEVELS; ++level) {
            final String segment = segment(name, level);
            final Resource existing = bucket.getChild(segment);
            bucket = existing != null ? existing
                : bucket.getResourceResolver().create(bucket, segment, Map.of("jcr:primaryType", bucketNodeType));
        }
        return bucket;
    }

    /**
     * The path of a node filed in a prefix tree, computed without reading the repository.
     *
     * @param rootPath the absolute path of the top of the prefix tree
     * @param name the name of the node, at least {@value #MINIMUM_NAME_LENGTH} uniformly distributed characters
     * @return an absolute path, whether or not anything exists there
     * @throws IllegalArgumentException if the name is too short to file
     */
    @NotNull
    public static String pathFor(@NotNull final String rootPath, @NotNull final String name)
    {
        return bucketPathFor(rootPath, name) + "/" + name;
    }

    /**
     * The path of the bucket a node filed in a prefix tree belongs in, computed without touching the repository.
     *
     * @param rootPath the absolute path of the top of the prefix tree
     * @param name the name of the node, at least {@value #MINIMUM_NAME_LENGTH} uniformly distributed characters
     * @return the bucket's absolute path, the root path followed by {@value #BUCKET_LEVELS} segments of
     *         {@value #SEGMENT_LENGTH} characters taken from the start of the name, whether or not the bucket exists
     * @throws IllegalArgumentException if the name is too short to file
     */
    @NotNull
    public static String bucketPathFor(@NotNull final String rootPath, @NotNull final String name)
    {
        checkName(name);
        final String prefix = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        return IntStream.range(0, BUCKET_LEVELS)
            .mapToObj(level -> segment(name, level))
            .collect(Collectors.joining("/", prefix, ""));
    }

    /**
     * One bucket, created and saved if it does not exist yet. If saving it conflicts with another session's commit,
     * the session's pending changes are discarded and the bucket is read again.
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
