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
package io.uhndata.iap.conditions.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The values of one side of a comparison, as computed by an
 * {@link io.uhndata.iap.conditions.spi.OperandResolver} from a {@code cond:ConditionOperand} definition. A resolver
 * returns the values in their natural stored types, optionally stating the {@link #getType() declared type} it is
 * authoritative about (e.g. the referenced question's data type); the evaluator then unifies the types of the two
 * sides and {@link #coerce(OperandType) coerces} both before handing them to an {@link Operator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Operand
{
    /** An operand with no values and no declared type. */
    public static final Operand EMPTY = new Operand(List.of(), null);

    private final List<Object> values;

    private final OperandType type;

    Operand(final List<Object> values, final OperandType type)
    {
        this.values = List.copyOf(values);
        this.type = type;
    }

    /**
     * Wrap one or many raw values, e.g. as read from a {@code ValueMap}, with no declared type.
     *
     * @param rawValue a single value, an array of values, or {@code null}
     * @return an operand, empty if there are no values
     */
    public static Operand of(final Object rawValue)
    {
        return of(rawValue, null);
    }

    /**
     * Wrap one or many raw values, e.g. as read from a {@code ValueMap}, stating the type they are meant to hold.
     *
     * @param rawValue a single value, an array of values, or {@code null}
     * @param declaredType the type the values are meant to hold, e.g. the referenced question's declared data
     *            type, or {@code null} if unknown
     * @return an operand, empty if there are no values
     */
    public static Operand of(final Object rawValue, final OperandType declaredType)
    {
        if (rawValue == null) {
            return declaredType == null ? EMPTY : new Operand(List.of(), declaredType);
        }
        final Stream<Object> rawValues = rawValue instanceof Object[]
            ? Arrays.stream((Object[]) rawValue) : Stream.of(rawValue);
        return new Operand(rawValues.filter(Objects::nonNull).collect(Collectors.toList()), declaredType);
    }

    /**
     * The type this operand's values are declared to hold, if any: either stated by the resolver that produced it,
     * or the type it was last {@link #coerce coerced} to.
     *
     * @return an operand type, or {@code null} if none was declared yet
     */
    public OperandType getType()
    {
        return this.type;
    }

    /**
     * The best known type of this operand's values: the {@link #getType() declared type} when available, otherwise
     * the type {@link OperandType#infer inferred} from the values themselves. Strings deliberately infer nothing —
     * they are the flexible side of a comparison, coercible to whatever the other side calls for.
     *
     * @return an operand type, or {@code null} if the type cannot be determined
     */
    public OperandType getEffectiveType()
    {
        if (this.type != null) {
            return this.type;
        }
        return this.values.isEmpty() ? null : OperandType.infer(this.values.get(0));
    }

    /**
     * Turn the raw values into objects of the given type, ready for comparison. Values that cannot be interpreted
     * as that type are dropped.
     *
     * @param target the type the values are coerced to
     * @return a new operand holding the coerced values
     */
    public Operand coerce(final OperandType target)
    {
        return new Operand(this.values.stream()
            .map(target::coerce)
            .filter(Objects::nonNull)
            .collect(Collectors.toList()), target);
    }

    /**
     * All the values of this operand. Only meaningful for comparison after {@link #coerce coercion}.
     *
     * @return a stream of values
     */
    @SuppressWarnings("unchecked")
    public Stream<Comparable<Object>> stream()
    {
        return this.values.stream().map(value -> (Comparable<Object>) value);
    }

    /**
     * One of the values of this operand. Only meaningful for comparison after {@link #coerce coercion}.
     *
     * @param index the position of the value to retrieve
     * @return the value at the given position
     */
    @SuppressWarnings("unchecked")
    public Comparable<Object> get(final int index)
    {
        return (Comparable<Object>) this.values.get(index);
    }

    /**
     * The number of values in this operand.
     *
     * @return a positive number, may be {@code 0}
     */
    public int size()
    {
        return this.values.size();
    }

    /**
     * Whether this operand holds no values at all.
     *
     * @return {@code true} if there are no values
     */
    public boolean isEmpty()
    {
        return this.values.isEmpty();
    }

    /**
     * The values as stored, without any comparability promise, for {@link Aggregator}s that fold them.
     *
     * @return a stream of raw values
     */
    Stream<Object> rawStream()
    {
        return this.values.stream();
    }
}
