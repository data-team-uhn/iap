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
package io.uhndata.iap.submissions.models;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.EntityHomepage;

/**
 * A Sling Model wrapping a {@code sub:SubmissionsHomepage} node, the root container of the {@code /Submissions}
 * tree.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = SubmissionsHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SubmissionsHomepage extends EntityHomepage
{
    /** The {@code sling:resourceType} of a {@code sub:SubmissionsHomepage} node. */
    public static final String RESOURCE_TYPE = "sub/SubmissionsHomepage";

    /**
     * The submissions filed under this homepage, wherever in its prefix tree they sit.
     *
     * <p>Submissions are spread over buckets named after the first characters of their names, so they are
     * descendants rather than children and finding them all means walking the tree. Nothing bounds how many there
     * are: this is for code that genuinely wants every one of them, and anything displaying a list should ask the
     * paginated listing endpoint instead, which queries by node type and never materializes more than a page.</p>
     *
     * @return a list of submissions, empty if none
     */
    @NotNull
    public List<Submission> getSubmissions()
    {
        return filed(this.resource).collect(Collectors.toList());
    }

    /**
     * The submissions at or below a node: the node's own submissions, plus whatever its buckets hold. A submission
     * is never a bucket, so the walk stops as soon as it finds one and cannot be led into a submission's own
     * children.
     *
     * @param node the node to look under
     * @return the submissions found
     */
    @NotNull
    private static Stream<Submission> filed(@NotNull final Resource node)
    {
        return StreamSupport.stream(node.getChildren().spliterator(), false)
            .flatMap(child -> child.isResourceType(Submission.RESOURCE_TYPE)
                ? Stream.of(child.adaptTo(Submission.class))
                : filed(child))
            .filter(Objects::nonNull);
    }
}
