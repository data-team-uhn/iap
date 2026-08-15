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
package io.uhndata.iap.i18n.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Which languages a deployment offers, and which one it writes its own logs in.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP languages",
    description = "The languages this deployment offers, and the one it writes server-facing text in")
public @interface LocalesConfiguration
{
    /**
     * The languages on offer, best first.
     *
     * @return language tags, the first of which is this deployment's default
     */
    @AttributeDefinition(name = "Available languages",
        description = "Language tags this deployment offers, best first. The first is the default, used for"
            + " anyone whose own preference cannot be honoured. Listing a language nothing is translated into"
            + " does not translate it; it only offers readers a way to reach an untranslated page.")
    String[] availableLocales() default { "en", "fr" };

    /**
     * The language server-facing text is written in.
     *
     * @return a language tag, or blank to follow the JVM
     */
    @AttributeDefinition(name = "Server language",
        description = "The language for logs and diagnostics — text addressed to whoever runs this"
            + " deployment rather than to anyone using it. Blank follows the JVM's own default, which is"
            + " worth setting explicitly where that default is not the language the logs are read in.")
    String systemLocale() default "";
}
