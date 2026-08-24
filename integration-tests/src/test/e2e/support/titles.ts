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
 * A test that creates content and then finds it by name cannot use a fixed title: the instance outlives the
 * test, so a retry runs against a repository that already holds whatever the failed attempt left behind. Two
 * rows then match, the lookup fails on strict mode instead of on whatever went wrong the first time, and the
 * retry can never pass — which also hides the original failure, the one worth reading.
 *
 * The suffix is short and the name still reads, because these titles appear in narrative tests and in the
 * screenshots of them.
 *
 * @param name what the test would have called it
 * @returns that name with a suffix unique to this attempt
 */
export function uniqueTitle(name: string): string {
  const suffix = Math.random().toString(36).slice(2, 7);
  return `${name} ${suffix}`;
}
