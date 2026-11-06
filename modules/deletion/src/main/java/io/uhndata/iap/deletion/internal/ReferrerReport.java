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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.ReferrerGroup;

/**
 * Describes the referencing resources that block a deletion, for the requesting user: grouped by node type, with a
 * few names per group, and in one sentence. A resource the user cannot read, an archived one, or one that fails to
 * describe itself is only counted.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReferrerReport
{
    /** How many resources of a group are named. */
    private static final int MAX_NAMES = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferrerReport.class);

    /** The property that names a resource, when it is set. The node name is used otherwise. */
    private static final String TITLE_PROPERTY = "title";

    private final List<ReferrerGroup> groups;

    private final long inaccessibleCount;

    /**
     * A referrer the requesting user can read, reduced to what the report needs.
     *
     * @param type the referrer's primary node type, such as {@code sub:Submission}
     * @param name the referrer's display name
     * @since 0.1.0
     */
    private record Described(String type, String name)
    {
    }

    /**
     * Describe the blocking referrers gathered in a plan.
     *
     * @param plan a resolved deletion plan
     */
    ReferrerReport(final DeletionPlan plan)
    {
        final List<Described> described = plan.getBlockingReferrers().values().stream()
            .map(node -> describe(node, plan.getUserSession()))
            .toList();
        this.inaccessibleCount = plan.getArchivedReferrers().size()
            + described.stream().filter(Objects::isNull).count();
        final Map<String, List<String>> namesByType = described.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(Described::type, TreeMap::new,
                Collectors.mapping(Described::name, Collectors.toList())));
        this.groups = namesByType.entrySet().stream()
            .map(group -> new ReferrerGroup(group.getKey(), humanize(group.getKey()),
                group.getValue().subList(0, Math.min(MAX_NAMES, group.getValue().size())), group.getValue().size()))
            .toList();
    }

    /**
     * The blocking referrers the requesting user can read, grouped by primary node type.
     *
     * @return an immutable list of groups, sorted by node type name; empty if there are none
     */
    List<ReferrerGroup> getGroups()
    {
        return this.groups;
    }

    /**
     * How many blocking referrers are counted but not named: those the requesting user cannot read, those in the
     * archive, and those that fail to describe themselves.
     *
     * @return the count; {@code 0} if every blocking referrer is named
     */
    long getInaccessibleCount()
    {
        return this.inaccessibleCount;
    }

    /**
     * All the blocking referrers in one sentence.
     *
     * @return the sentence, such as
     *         {@code "This item is referenced by 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)."};
     *         empty if no referrer blocks the deletion
     */
    String summary()
    {
        final List<String> parts = new ArrayList<>(this.groups.stream().map(ReferrerReport::describeGroup).toList());
        if (this.inaccessibleCount > 0) {
            parts.add(this.inaccessibleCount + (this.inaccessibleCount == 1 ? " other item" : " other items")
                + " you cannot see");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "This item is referenced by " + humanJoin(parts) + ".";
    }

    private static Described describe(final Node node, final Session userSession)
    {
        try {
            if (!userSession.nodeExists(node.getPath())) {
                return null;
            }
            return new Described(node.getPrimaryNodeType().getName(), displayName(node));
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to describe a resource blocking a deletion: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String displayName(final Node node) throws RepositoryException
    {
        if (node.hasProperty(TITLE_PROPERTY) && !node.getProperty(TITLE_PROPERTY).isMultiple()) {
            return node.getProperty(TITLE_PROPERTY).getString();
        }
        return node.getName();
    }

    /**
     * A node type name in words, such as {@code submission} for {@code sub:Submission} and {@code workflow version}
     * for {@code wf:WorkflowVersion}.
     */
    private static String humanize(final String nodeType)
    {
        final String local = nodeType.substring(nodeType.indexOf(':') + 1);
        return local.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String describeGroup(final ReferrerGroup group)
    {
        final StringBuilder result = new StringBuilder();
        result.append(group.getCount()).append(' ')
            .append(group.getCount() == 1 ? group.getLabel() : pluralize(group.getLabel()));
        if (!group.getNames().isEmpty()) {
            result.append(" (").append(String.join(", ", group.getNames()));
            if (group.getCount() > group.getNames().size()) {
                result.append(", …");
            }
            result.append(')');
        }
        return result.toString();
    }

    private static String pluralize(final String label)
    {
        if (label.endsWith("y") && label.length() > 1 && "aeiou".indexOf(label.charAt(label.length() - 2)) < 0) {
            return label.substring(0, label.length() - 1) + "ies";
        }
        if (Stream.of("s", "x", "z", "ch", "sh").anyMatch(label::endsWith)) {
            return label + "es";
        }
        return label + "s";
    }

    private static String humanJoin(final List<String> parts)
    {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
