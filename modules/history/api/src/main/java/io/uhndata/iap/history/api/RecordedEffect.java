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

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * What an action did to one resource, as the code that did it names it: which resource, and the part it played.
 *
 * <p>
 * The role is the meaning of the change, which only that code knows. Activating a workflow version retires the one
 * it replaces, and the role says which is which. What changed is not named here: the commit itself records it.
 * </p>
 *
 * @param path the absolute path of the affected node; a part of an entity stands for the entity it belongs to
 * @param role a word for the part the resource played, in the vocabulary of the operation rather than of the storage,
 *            such as {@code retired}, {@code activated} or {@code submitted}
 * @version $Id$
 * @since 0.1.0
 */
public record RecordedEffect(@NotNull String path, @NotNull String role)
{
    /**
     * Refuses a missing path or role.
     */
    public RecordedEffect
    {
        Objects.requireNonNull(path, "an effect has to say which resource it is about");
        Objects.requireNonNull(role, "an effect has to say what part the resource played");
    }
}
