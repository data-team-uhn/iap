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

// A date and time as the reader's locale writes them. The value is an ISO 8601 string, as the
// repository serializes dates. A value that is not a date gives an empty string, not "Invalid Date".
export function formatDate(value: unknown): string {
  const date = typeof value === "string" && value !== "" ? new Date(value) : undefined;
  return date === undefined || Number.isNaN(date.getTime()) ? "" : date.toLocaleString();
}
