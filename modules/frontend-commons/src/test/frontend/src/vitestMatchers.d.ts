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

import type { TestingLibraryMatchers } from "@testing-library/jest-dom/matchers";

// Gives every expect() the jest-dom matchers. Vitest 5 takes custom matchers through Matchers<R, T>,
// while jest-dom still augments the one-parameter Assertion<T> of Vitest 4, which no longer merges.
declare module "vitest" {
  // The type parameters must repeat Vitest's own declaration for the two to merge
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type, unused-imports/no-unused-vars
  interface Matchers<R extends void | Promise<void> = void | Promise<void>, T = unknown>
    extends TestingLibraryMatchers<unknown, R> {}
}
