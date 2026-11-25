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
 * A model's reply, and what is known of the model that wrote it. A provider can swap the model behind a name, so
 * what it says answered is kept beside what was asked for.
 *
 * @param text the reply
 * @param model the model asked for, as configured; {@code null} when the client does not say
 * @param servedModel the model the provider says answered, which may name a more exact version; {@code null} when
 *     it does not say
 * @param systemFingerprint the provider's fingerprint of its serving setup, which changes when the setup does;
 *     {@code null} when it gives none
 * @version $Id$
 * @since 0.1.0
 */
public record LLMReply(@NotNull String text, @Nullable String model, @Nullable String servedModel,
    @Nullable String systemFingerprint)
{
}
