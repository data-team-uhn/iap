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

// The lifecycle tags' definitions as `/Tags.search.json?category=lifecycle` answers them, which is
// what a version's lifecycle chip is labelled from.
export const LIFECYCLE_TAGS = {
  tags: [
    { name: "draft", label: "Draft" },
    { name: "trial", label: "Trial" },
    { name: "active", label: "Active" },
    { name: "retired", label: "Retired" },
  ],
};

// Whether a request is the chip asking for those definitions, rather than for the workflow
export const isTagSearch = (url: string): boolean => url.startsWith("/Tags.search.json");
