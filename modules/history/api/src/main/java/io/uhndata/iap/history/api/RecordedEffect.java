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
 * The role is the point. An action affecting several resources has usually done something different to each of them —
 * retiring one workflow version while activating another — and a record that only listed what it touched could not say
 * so. Use the vocabulary of the operation, not of the storage: {@code retired}, {@code activated}, {@code submitted}.
 * </p>
 *
 * <p>
 * The changes are property <em>names</em>. Never put a value in here: the record is meant to stay small, to stay
 * readable at a glance, and to say nothing that would have to be redacted if the content it describes were ever purged.
 * </p>
 *
 * @param subject the identifier of the affected resource
 * @param subjectPath where it was when this happened
 * @param subjectType what it was when this happened
 * @param role the part it played in the action
 * @param changes the names of the properties that changed on it
 * @version $Id$
 * @since 0.1.0
 */
public record RecordedEffect(@NotNull String subject, @NotNull String subjectPath, @NotNull String subjectType,
    @NotNull String role, @NotNull List<String> changes)
{
    /**
     * Checks what has to be there and takes a copy of what a caller could otherwise change afterwards.
     */
    public RecordedEffect
    {
        Objects.requireNonNull(subject, "an effect has to say which resource it is about");
        Objects.requireNonNull(subjectPath, "an effect has to say where the resource was");
        Objects.requireNonNull(subjectType, "an effect has to say what the resource was");
        Objects.requireNonNull(role, "an effect has to say what part the resource played");
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /**
     * The effect on a resource that is still there to be asked about itself, which is the usual case: the identifier,
     * the path and the type are read off the node rather than passed in, so they cannot disagree with it.
     *
     * @param subject the affected node, which must be referenceable
     * @param role the part it played in the action
     * @param changes the names of the properties that changed on it
     * @return an effect describing it
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
