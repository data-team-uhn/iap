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
package io.uhndata.iap.history.api;

import java.util.List;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.jetbrains.annotations.NotNull;

/**
 * What an action did to one resource: which resource, and the part it played.
 *
 * <p>
 * An action affecting several resources usually does something different to each: activating a workflow version
 * retires the one it replaces. The role says which.
 * </p>
 *
 * <p>
 * The changes are property names. Never put a value in here: a record that carried one would have to be redacted
 * when the content it describes was purged.
 * </p>
 *
 * @param subject the {@code jcr:uuid} of the affected node, which must be referenceable
 * @param subjectPath the node's absolute path when the action happened; the node may have moved since, for example
 *            when it was archived
 * @param subjectType the node's primary node type when the action happened, such as {@code sub:Submission}
 * @param role a word for the part the resource played, in the vocabulary of the operation rather than of the storage,
 *            such as {@code retired}, {@code activated} or {@code submitted}
 * @param changes the names of the JCR properties the action changed on the node, such as {@code tags}; empty if it
 *            changed none
 * @version $Id$
 * @since 0.1.0
 */
public record RecordedEffect(@NotNull String subject, @NotNull String subjectPath, @NotNull String subjectType,
    @NotNull String role, @NotNull List<String> changes)
{
    /**
     * Refuses a missing subject, path, type, role or change list, and copies the change list so that the caller
     * cannot alter it afterwards.
     */
    public RecordedEffect
    {
        Objects.requireNonNull(subject, "an effect has to say which resource it is about");
        Objects.requireNonNull(subjectPath, "an effect has to say where the resource was");
        Objects.requireNonNull(subjectType, "an effect has to say what the resource was");
        Objects.requireNonNull(role, "an effect has to say what part the resource played");
        changes = List.copyOf(Objects.requireNonNull(changes, "an effect has to say which properties changed"));
    }

    /**
     * The effect on a node, with its {@code jcr:uuid}, path and primary node type read off the node itself.
     *
     * @param subject the affected node, which must be referenceable
     * @param role a word for the part the node played, such as {@code retired}
     * @param changes the names of the JCR properties the action changed on the node; none if it changed none
     * @return an effect carrying the node's {@code jcr:uuid}, path and primary node type as they are now
     * @throws RepositoryException if the node cannot be asked for its identifier, path or type
     */
    @NotNull
    public static RecordedEffect on(@NotNull final Node subject, @NotNull final String role,
        @NotNull final String... changes) throws RepositoryException
    {
        return new RecordedEffect(subject.getIdentifier(), subject.getPath(),
            subject.getPrimaryNodeType().getName(), role, List.of(changes));
    }
}
