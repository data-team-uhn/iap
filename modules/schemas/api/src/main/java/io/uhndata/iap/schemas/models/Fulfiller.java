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
package io.uhndata.iap.schemas.models;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * Something filed against a requirement, saying so itself.
 *
 * <p>Whatever answers a requirement — a document, a decision, a set of answers, a choice of data — is a part of
 * the thing carrying it, and knows which requirement it is for. Asking each of them is how what is still missing
 * is worked out, and it is deliberately the opposite of asking each requirement to go looking: a kind of answer
 * declared by another module is counted like any other, because saying what it answers is its own business rather
 * than something the walk has to recognise.</p>
 *
 * <p>Two questions, not one. {@link #getFulfills} is what it is <em>for</em>, and {@link #isFulfilling} is whether
 * it actually meets it — a refused decision names the approval it answers and does not grant it, and a selection
 * that was emptied names the requirement it answers and chooses nothing.</p>
 *
 * <p>How either is stored is each kind's own affair. Nothing here reads a property, which is why two kinds
 * spelling it differently would still both work — though they no longer do.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Fulfiller extends EntityPart
{
    /**
     * The requirement this was filed against.
     *
     * @return a requirement, or {@code null} where it names none or the reference cannot be resolved
     */
    @Nullable
    public abstract Requirement getFulfills();

    /**
     * Whether this actually meets the requirement it names, rather than merely being filed against it.
     *
     * <p>Filing something is usually meeting it, which is why this says so unless a kind knows better.</p>
     *
     * @return {@code true} if the requirement is met as far as this is concerned
     */
    public boolean isFulfilling()
    {
        return true;
    }

    /**
     * Whether this was filed against one particular requirement.
     *
     * @param requirement the requirement in question
     * @return {@code true} if this names that requirement
     */
    public boolean answers(@NotNull final Requirement requirement)
    {
        final Requirement named = this.getFulfills();
        return named != null && requirement.getPath().equals(named.getPath());
    }
}
