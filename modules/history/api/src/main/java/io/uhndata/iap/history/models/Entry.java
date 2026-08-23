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
 * What one {@link Action} did to one resource.
 *
 * <p>
 * The action says why something happened; this says what it did, and to what. They are separate because one action
 * commonly affects several resources in different ways, and neither shape can state that alone: a list of touched
 * identifiers cannot say which workflow version was retired and which activated, while a record per resource cannot say
 * that both happened together for one reason. {@link #getRole()} is the property carrying that meaning.
 * </p>
 *
 * <p>
 * Nothing here holds a value that changed, only the names of the properties that changed. What the content actually
 * looked like is the {@link #getSnapshot() snapshot}, if the process asked for one.
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

    /** The identifier of the resource this is about. */
    @ValueMapValue
    private String subject;

    /** Where it was at the time. */
    @ValueMapValue
    private String subjectPath;

    /** What it was at the time. */
    @ValueMapValue
    private String subjectType;

    /** The part it played. */
    @ValueMapValue
    private String role;

    /** Which properties changed. */
    @ValueMapValue
    private String[] changes;

    /** The version holding the content as it was afterwards. */
    @ValueMapValue
    private String snapshot;

    /**
     * The identifier of the resource this entry is about.
     *
     * <p>
     * An identifier rather than a reference, deliberately: an enforced reference would make the record pin what it
     * describes and refuse to let anybody delete it, and the record has to be able to outlive its subject.
     * </p>
     *
     * @return an identifier, empty only in a malformed record
     */
    @NotNull
    public String getSubject()
    {
        return this.subject == null ? "" : this.subject;
    }

    /**
     * Where the subject was when this happened. A copy, because paths move: content is archived, restored, renamed.
     *
     * @return a path, empty only in a malformed record
     */
    @NotNull
    public String getSubjectPath()
    {
        return this.subjectPath == null ? "" : this.subjectPath;
    }

    /**
     * What the subject was when this happened. A copy, because it is how an entry stays legible once its subject no
     * longer exists to be asked.
     *
     * @return a node type name, empty only in a malformed record
     */
    @NotNull
    public String getSubjectType()
    {
        return this.subjectType == null ? "" : this.subjectType;
    }

    /**
     * The part this resource played in the action — {@code submitted}, {@code retired}, {@code activated}.
     *
     * <p>
     * Free-form on purpose: the vocabulary belongs to the workflow definitions, which grow operations of their own, and
     * constraining it in the node type would fail the commit that records a change rather than the change itself.
     * </p>
     *
     * @return a role, empty only in a malformed record
     */
    @NotNull
    public String getRole()
    {
        return this.role == null ? "" : this.role;
    }

    /**
     * The names of the properties this action changed on this resource — never what they changed to. That keeps the
     * record small and readable, and says something the snapshot beside it cannot.
     *
     * @return the property names, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getChanges()
    {
        return this.changes == null ? List.of() : List.of(this.changes);
    }

    /**
     * The identifier of the JCR version holding this resource's content as it was after the action — the join to the
     * other half of the record.
     *
     * <p>
     * Usually absent, and that is normal rather than a gap: every action is logged, while snapshots are taken only
     * where the process says a milestone is. Whether an absence means "none wanted" or "not taken yet" is answered by
     * {@link Action#isComplete()}.
     * </p>
     *
     * @return a version identifier, or {@code null} when this action snapshotted nothing here
     */
    @Nullable
    public String getSnapshot()
    {
        return this.snapshot;
    }

    /**
     * The action this entry is part of.
     *
     * @return the owning action, or {@code null} if this entry is somehow not filed under one
     */
    @Nullable
    public Action getAction()
    {
        return this.getParent(Action.RESOURCE_TYPE, Action.class);
    }

    /**
     * Anything said about this change afterwards.
     *
     * <p>
     * This is where a later thought about an earlier change has to live: a version and its frozen node are both
     * entirely protected, so version storage can never be annotated.
     * </p>
     *
     * @return the annotations, possibly empty, never {@code null}
     */
    @NotNull
    public List<Annotation> getAnnotations()
    {
        return this.getChildren(Annotation.RESOURCE_TYPE, Annotation.class);
    }
}
