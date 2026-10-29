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

/**
 * A title no other attempt will have used.
 *
 * A test that creates content and then finds it by name needs a title of its own. The instance outlives the
 * test, so a retry finds whatever the failed attempt left behind. A fixed title then matches two rows, and the
 * lookup fails in strict mode. The retry cannot pass, and its failure hides the first one.
 *
 * The suffix is short, so the name still reads in narrative tests and their screenshots.
 *
 * @param name what the test would have called it
 * @returns that name with a suffix unique to this attempt
 */
export function uniqueTitle(name: string): string {
  const suffix = Math.random().toString(36).slice(2, 7);
  return `${name} ${suffix}`;
}
