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
package io.uhndata.iap.extraction.internal;

/**
 * The category a model placed a proposal under: a live leaf of the categories tree, by path, and how sure the
 * model was of it. Only a category that is really in the tree is ever picked; an answer naming anything else is
 * no pick at all, and the choice is left to a person.
 *
 * @param path the category's path under {@code /Categories}
 * @param confidence the model's own confidence in the pick, 0 to 1
 * @version $Id$
 * @since 0.1.0
 */
public record CategoryPick(String path, double confidence)
{
}
