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
 * Turns the referencing resources blocking a deletion into a report the requesting user can act on: groups by
 * type, a few display names per group, and one readable sentence. Resources the user cannot see, and resources
 * that fail to describe themselves, are only counted, so one odd node never degrades the whole report.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReferrerReport
{
    /** How many members of a group are named before the rest is elided. */
    private static final int MAX_NAMES = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferrerReport.class);

    /** The property preferred over the node name as a display name, when present. */
    private static final String TITLE_PROPERTY = "title";

    private final List<ReferrerGroup> groups;

    private final long inaccessibleCount;

    /**
     * A visible referrer reduced to what the report needs.
     *
     * @param type the referrer's primary node type
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
     * The visible blocking referrers, grouped by primary node type.
     *
     * @return an immutable list of groups, in type order
     */
    List<ReferrerGroup> getGroups()
    {
        return this.groups;
    }

    /**
     * How many blocking referrers are counted but not named: hidden from the requesting user by access control,
     * sitting in the archive, or failing to describe themselves.
     *
     * @return a number, {@code 0} when everything is visible
     */
    long getInaccessibleCount()
    {
        return this.inaccessibleCount;
    }

    /**
     * One readable sentence describing all the blockers.
     *
     * @return e.g. {@code "This item is referenced by 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)."},
     *         or an empty string when nothing blocks the deletion
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
     * Turn a node type name into a label, e.g. {@code sub:Submission} into {@code submission} and
     * {@code wf:WorkflowVersion} into {@code workflow version}.
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
