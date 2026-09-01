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

// Where each level of the tree starts, so that a row's checkbox lines up under the label of the level
// above it. Narrower on a phone.

/** The left padding of a database row, a collection row and a field row, in that order. */
export const INDENT_BY_DEPTH = [
  { xs: "12px", md: "18px" },
  { xs: "14px", md: "42px" },
  { xs: "34px", md: "70px" },
] as const;

/** The right padding every row shares. */
export const ROW_END_PADDING = { xs: 1.5, md: 2.25 };
