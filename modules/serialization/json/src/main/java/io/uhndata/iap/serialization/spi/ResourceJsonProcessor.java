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
package io.uhndata.iap.serialization.spi;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;

import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shapes part of the JSON a resource is served as. A chain of processors builds that JSON, and each one adds, changes
 * or drops what the processors before it produced.
 *
 * <p>
 * The selectors choose the processors by name: {@code /path/to/resource.deep.simple.json} enables {@code deep} and
 * {@code simple}. Java code passes them in the path it resolves, as in
 * {@code resourceResolver.resolve("/path/to/resource.deep.simple")}. Some processors are
 * {@link #isEnabledByDefault(Resource) enabled by default}, such as {@code properties}, which writes the value of each
 * property. A {@code -} before a name disables it: {@code /path/to/resource.-dereference.json}.
 * </p>
 *
 * <p>
 * Several processors may share a name, and the name enables them all. A module can give a name such as
 * {@code simple} a meaning for its own node types this way.
 * </p>
 *
 * <p>
 * Each node is serialized in order: {@link #enter}, then each property through {@link #processProperty} and
 * {@link #processPropertyName}, then each child through {@link #processChild}, then {@link #leave}. The enabled
 * processors run in ascending order of {@link #getPriority() priority}. For a property or a child, each one receives
 * the value the one before it returned, and the first receives {@code null}. A final {@code null} leaves the property
 * or child out of the JSON. {@code JsonValue.NULL} writes it as {@code null}.
 * </p>
 *
 * <p>
 * A child's key is its node name, and a property's key is the name {@link #processPropertyName} gives it. Only
 * {@link #enter} and {@link #leave} can write other keys.
 * </p>
 *
 * <p>
 * The {@code serializeNode} function each hook receives serializes another node within the same serialization. It
 * returns the node's JSON object, or only its path for a node past the requested depth or already being serialized
 * higher up. It returns {@code null} for a node it cannot read.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ResourceJsonProcessor
{
    /**
     * The name the selectors enable or disable this processor by.
     *
     * @return the name, such as {@code deep}
     */
    @NotNull
    String getName();

    /**
     * Where this processor runs in the chain. A processor runs after those with a lower priority, and receives what
     * they returned. {@code properties} writes each property's stored value at priority {@code 0}. Run after it to
     * change a value, or before it to write a value another way.
     *
     * @return any {@code int}; processors of equal priority run in no defined order
     */
    int getPriority();

    /**
     * Whether this processor may run when serializing the given resource. A processor that cannot process the
     * resource does not run, even if the selectors name it. The default allows every resource.
     *
     * <p>
     * It is asked about the requested resource only, never about the nodes inside it. A processor for a node type
     * that can appear nested checks each node in its hooks instead.
     * </p>
     *
     * @param resource the requested resource
     * @return {@code true} if this processor may run
     */
    default boolean canProcess(@NotNull final Resource resource)
    {
        return true;
    }

    /**
     * Whether this processor runs when the selectors do not name it. A {@code -} selector before its name still
     * disables it. It is asked about the requested resource only. The default is {@code false}.
     *
     * @param resource the requested resource
     * @return {@code true} if this processor runs unless the selectors disable it
     */
    default boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        return false;
    }

    /**
     * Called once, before the requested resource is serialized. One instance serves every serialization, and
     * serializations run concurrently.
     *
     * @param resource the requested resource
     */
    default void start(@NotNull final Resource resource)
    {
        return;
    }

    /**
     * Called when a node's serialization begins, before its properties. The processors after this one see what it
     * adds to the builder. The default does nothing.
     *
     * @param node the node being serialized: the requested resource's node, or one nested in it
     * @param input the node's JSON so far; empty for the first processor
     * @param serializeNode serializes another node within this serialization, as the class description says
     */
    default void enter(@NotNull final Node node, @NotNull final JsonObjectBuilder input,
        @NotNull final Function<Node, JsonValue> serializeNode)
    {
        return;
    }

    /**
     * Called for each property of a serialized node, to give the property's JSON value. The default returns
     * {@code input} unchanged.
     *
     * @param node the node that has the property
     * @param property the property
     * @param input the value the processors before this one returned; {@code null} if they returned none
     * @param serializeNode serializes another node within this serialization, as the class description says
     * @return the property's value, of any JSON type; {@code null} to leave the property out
     */
    @Nullable
    default JsonValue processProperty(@NotNull final Node node, @NotNull final Property property,
        @Nullable final JsonValue input, @NotNull final Function<Node, JsonValue> serializeNode)
    {
        return input;
    }

    /**
     * Called for each property of a serialized node, to give the key the property is written under. The first
     * processor receives the property's name. The default returns {@code input} unchanged.
     *
     * @param node the node that has the property
     * @param property the property
     * @param input the key the processors before this one returned; {@code null} if one of them left the property out
     * @return the key; {@code null} to leave the property out
     */
    @Nullable
    default String processPropertyName(@NotNull final Node node, @NotNull final Property property,
        @Nullable final String input)
    {
        return input;
    }

    /**
     * Called for each child of a serialized node, to give the child's JSON value. The default returns {@code input}
     * unchanged.
     *
     * @param node the node that has the child
     * @param child the child node
     * @param input the value the processors before this one returned; {@code null} if they returned none
     * @param serializeNode serializes another node within this serialization, as the class description says
     * @return the child's value, of any JSON type; {@code null} to leave the child out
     */
    @Nullable
    default JsonValue processChild(@NotNull final Node node, @NotNull final Node child,
        @Nullable final JsonValue input, @NotNull final Function<Node, JsonValue> serializeNode)
    {
        return input;
    }

    /**
     * Called when a node's serialization ends, after its children. The builder holds the node's properties and
     * children, and what the processors before this one added. The processors after this one see what it adds. The
     * default does nothing.
     *
     * @param node the node being serialized: the requested resource's node, or one nested in it
     * @param json the node's JSON so far
     * @param serializeNode serializes another node within this serialization, as the class description says
     */
    default void leave(@NotNull final Node node, @NotNull final JsonObjectBuilder json,
        @NotNull final Function<Node, JsonValue> serializeNode)
    {
        return;
    }

    /**
     * Called once, after the requested resource is serialized, even if serializing it failed. Release here what
     * {@link #start} set up.
     *
     * @param resource the requested resource
     */
    default void end(@NotNull final Resource resource)
    {
        return;
    }
}
