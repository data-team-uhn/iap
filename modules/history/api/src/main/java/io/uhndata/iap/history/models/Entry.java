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
package io.uhndata.iap.history.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * What one {@link Action} did to one resource, and {@link #getRole() the part} that resource played.
 *
 * <p>
 * What the content looked like afterwards is the {@link #getSnapshot() snapshot}, if the workflow asked for one.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Entry.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Entry extends Content
{
    /** The Sling resource type of one resource's part in an action. */
    public static final String RESOURCE_TYPE = "hist/Entry";

    /** The name of the property holding the affected node's {@code jcr:uuid}. */
    public static final String SUBJECT_PROPERTY = "subject";

    /** The name of the property holding the path the node had when the action happened. */
    public static final String SUBJECT_PATH_PROPERTY = "subjectPath";

    /** The name of the property holding the node's primary node type when the action happened. */
    public static final String SUBJECT_TYPE_PROPERTY = "subjectType";

    /** The name of the property holding the part the resource played. */
    public static final String ROLE_PROPERTY = "role";

    /** The name of the property holding the names of the JCR properties that changed. */
    public static final String CHANGES_PROPERTY = "changes";

    /** The name of the property holding the {@code jcr:uuid} of the JCR version taken afterwards. */
    public static final String SNAPSHOT_PROPERTY = "snapshot";

    @ValueMapValue(name = SUBJECT_PROPERTY)
    private String subject;

    @ValueMapValue(name = SUBJECT_PATH_PROPERTY)
    private String subjectPath;

    @ValueMapValue(name = SUBJECT_TYPE_PROPERTY)
    private String subjectType;

    @ValueMapValue(name = ROLE_PROPERTY)
    private String role;

    @ValueMapValue(name = CHANGES_PROPERTY)
    private String[] changes;

    @ValueMapValue(name = SNAPSHOT_PROPERTY)
    private String snapshot;

    /**
     * The resource this entry is about.
     *
     * @return the {@code jcr:uuid} of the affected node, which outlives any move; empty only in a malformed record
     */
    @NotNull
    public String getSubject()
    {
        return this.subject == null ? "" : this.subject;
    }

    /**
     * Where {@link #getSubject() the resource} was when the action happened.
     *
     * @return the node's absolute path at that time, which it may have left since, for example when it was archived;
     *         empty only in a malformed record
     */
    @NotNull
    public String getSubjectPath()
    {
        return this.subjectPath == null ? "" : this.subjectPath;
    }

    /**
     * What kind of resource {@link #getSubject() the resource} was when the action happened.
     *
     * @return the node's primary node type, such as {@code sub:Submission}; empty only in a malformed record
     */
    @NotNull
    public String getSubjectType()
    {
        return this.subjectType == null ? "" : this.subjectType;
    }

    /**
     * The part this resource played in the action, which tells apart resources one action treated differently: the
     * workflow version it retired and the one it activated.
     *
     * @return a free-form word in the vocabulary of the operation, which the workflow definitions own, such as
     *         {@code submitted}, {@code retired} or {@code activated}; empty only in a malformed record
     */
    @NotNull
    public String getRole()
    {
        return this.role == null ? "" : this.role;
    }

    /**
     * Which properties this action changed on this resource. The values are never recorded, only the names.
     *
     * @return the names of the JCR properties, such as {@code tags}; empty if the action changed none
     */
    @NotNull
    public List<String> getChanges()
    {
        return this.changes == null ? List.of() : List.of(this.changes);
    }

    /**
     * The JCR version holding this resource's content as it was after the action.
     *
     * <p>
     * Usually absent: versions are taken only where a workflow declares a milestone. {@link Action#isComplete()} says
     * whether an absence means none was wanted or none has been taken yet.
     * </p>
     *
     * @return the {@code jcr:uuid} of that version, or {@code null} if none was taken of this resource
     */
    @Nullable
    public String getSnapshot()
    {
        return this.snapshot;
    }

    /**
     * The action this entry is part of.
     *
     * @return the {@code hist:Action} this entry is a child of, or {@code null} if it is not a child of one, which
     *         happens only in a malformed record
     */
    @Nullable
    public Action getAction()
    {
        return this.getParent(Action.RESOURCE_TYPE, Action.class);
    }

    /**
     * The notes people added about this change after it was recorded.
     *
     * @return the annotations, in no particular order; empty if nobody added any
     */
    @NotNull
    public List<Annotation> getAnnotations()
    {
        return this.getChildren(Annotation.RESOURCE_TYPE, Annotation.class);
    }
}
