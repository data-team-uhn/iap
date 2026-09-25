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
package io.uhndata.iap.llm;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-call overrides for a single {@link LLMClient} request. Every override is optional. Two overrides are
 * supported:
 * <ul>
 * <li>the maximum number of output tokens, which is the only place this ceiling is set: a caller knows what
 * shape of answer it asked for, and the same number tells {@link CallBudget} how much of the context window
 * to keep free for it. Unset, the request carries no ceiling and the provider applies its own;</li>
 * <li>a JSON Schema the provider must constrain the response to (structured outputs), so the reply is guaranteed
 * to be a JSON object of the required shape rather than free text that has to be parsed defensively.</li>
 * </ul>
 * Instances are immutable; build them with {@link #builder()}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class LLMRequestOptions
{
    private final Long maxOutputTokens;

    private final String responseSchemaName;

    private final String responseSchema;

    private LLMRequestOptions(final Builder builder)
    {
        this.maxOutputTokens = builder.maxOutputTokens;
        this.responseSchemaName = builder.responseSchemaName;
        this.responseSchema = builder.responseSchema;
    }

    /**
     * A builder for assembling request options.
     *
     * @return a new, empty builder
     */
    @NotNull
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * The maximum number of output tokens this call asks for.
     *
     * @return the ceiling, or {@code null} when this call sets none
     */
    @Nullable
    public Long getMaxOutputTokens()
    {
        return this.maxOutputTokens;
    }

    /**
     * The name the provider associates with the response JSON Schema (structured outputs).
     *
     * @return the schema name, or {@code null} when no schema override is set
     */
    @Nullable
    public String getResponseSchemaName()
    {
        return this.responseSchemaName;
    }

    /**
     * The JSON Schema, as a raw JSON string, the provider must constrain the response to (structured outputs).
     *
     * @return the schema JSON, or {@code null} when no schema override is set
     */
    @Nullable
    public String getResponseSchema()
    {
        return this.responseSchema;
    }

    /**
     * Whether this call requests a schema-constrained (structured-output) response.
     *
     * @return {@code true} when both a schema name and a schema body are set
     */
    public boolean hasResponseSchema()
    {
        return this.responseSchema != null && !this.responseSchema.isBlank()
            && this.responseSchemaName != null && !this.responseSchemaName.isBlank();
    }

    /**
     * Builder for {@link LLMRequestOptions}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private Long maxOutputTokens;

        private String responseSchemaName;

        private String responseSchema;

        private Builder()
        {
        }

        /**
         * Set the per-call maximum number of output tokens.
         *
         * @param tokens the maximum number of tokens to generate; must be positive
         * @return this builder
         * @throws IllegalArgumentException when {@code tokens} is not positive
         */
        @NotNull
        public Builder maxOutputTokens(final long tokens)
        {
            if (tokens <= 0) {
                // A ceiling computed as "context limit minus prompt tokens" going negative is the realistic
                // way here. Left unchecked it produced a non-null override, which beats the model's own
                // ceiling and reaches the provider as max_tokens 0 -- an empty completion.
                throw new IllegalArgumentException("maxOutputTokens must be positive; got " + tokens);
            }
            this.maxOutputTokens = tokens;
            return this;
        }

        /**
         * Request a schema-constrained (structured-output) response.
         *
         * @param name the name the provider associates with the schema (e.g. {@code document_summary})
         * @param schema the JSON Schema, as a raw JSON string, the response must conform to
         * @return this builder
         */
        @NotNull
        public Builder jsonSchema(@Nullable final String name, @Nullable final String schema)
        {
            this.responseSchemaName = name;
            this.responseSchema = schema;
            return this;
        }

        /**
         * Build an immutable {@link LLMRequestOptions} from this builder's state.
         *
         * @return the assembled request options
         */
        @NotNull
        public LLMRequestOptions build()
        {
            return new LLMRequestOptions(this);
        }
    }
}
