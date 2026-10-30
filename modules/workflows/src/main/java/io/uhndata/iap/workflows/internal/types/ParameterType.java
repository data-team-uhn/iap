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
package io.uhndata.iap.workflows.internal.types;

import java.util.function.Consumer;

import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One kind of value a workflow vocabulary parameter can hold, named by the parameter's {@code dataType}. The parser
 * hands each value a diagram gives such a parameter to the type, and stores what the type returns.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParameterType
{
    /**
     * The JCR type a value of this kind is stored as.
     *
     * @return one of the {@link javax.jcr.PropertyType} constants, e.g. {@link javax.jcr.PropertyType#LONG}
     */
    int storageType();

    /**
     * Turns one value, as written in the diagram, into what is stored. A value that cannot be stored is reported
     * and dropped. A value that can be stored but is suspect is reported and returned.
     *
     * @param raw one value as the diagram writes it, trimmed and never blank
     * @param declaration the {@code wf:Parameter} node declaring the parameter, which a type reads its own
     *            settings from, such as the node type a reference must point at
     * @param root the committed repository root, for a value that names something in the repository
     * @param report where a problem with the value is reported, as a sentence fragment such as {@code a parameter
     *            has a value that is not a boolean}
     * @return the value to store, of the Java class Oak keeps the {@link #storageType()} in, or {@code null} to drop
     *         the value
     */
    @Nullable
    Object convert(@NotNull String raw, @NotNull NodeState declaration, @NotNull NodeState root,
        @NotNull Consumer<String> report);
}
