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

import io.uhndata.iap.workflows.models.Parameter;

/**
 * Converts the text a diagram holds for a vocabulary parameter into a typed JCR value. The implementation is selected
 * using the {@code dataType} of the parameter's declaration, and is given the declaration's settings when it is
 * made.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParameterType
{
    /**
     * The type of one parameter's values. The node type constrains {@code dataType} to the names handled here.
     *
     * @param dataType the declaration's {@code dataType}, such as {@code long}, or {@code null} for text, which the
     *            node type autocreates
     * @param referenceType the declaration's {@code referenceType}, which only a reference reads, or {@code null}
     *            when it names none
     * @return the parameter type
     */
    @NotNull
    static ParameterType of(@Nullable final String dataType, @Nullable final String referenceType)
    {
        return switch (dataType == null ? Parameter.TEXT_DATA_TYPE : dataType) {
            case Parameter.BOOLEAN_DATA_TYPE -> new BooleanParameterType();
            case Parameter.LONG_DATA_TYPE -> new LongParameterType();
            case Parameter.REFERENCE_DATA_TYPE -> new ReferenceParameterType(referenceType);
            case Parameter.PRINCIPAL_DATA_TYPE -> new PrincipalParameterType();
            default -> new TextParameterType();
        };
    }

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
     * @param root the committed repository root ({@code /}), for a value that needs repository access to parse,
     *            such as a reference to a node
     * @param report where a problem with the value is reported, as a sentence fragment such as {@code a parameter
     *            has a value that is not a whole number}
     * @return the value to store, of the Java class Oak keeps the {@link #storageType()} in, or {@code null} to drop
     *         the value
     */
    @Nullable
    Object convert(@NotNull String raw, @NotNull NodeState root, @NotNull Consumer<String> report);
}
