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

import { type AuthenticatedFetch } from "../reLogin";
import { type Choice } from "./choices";

/** Where the choices of a search come from. */
export interface ChoiceSource {
  /** Names what the source offers: two sources with the same key offer the same choices. */
  key: string;
  /** Every choice the source offers. */
  search: (doFetch: AuthenticatedFetch) => Promise<Choice[]>;
  /** How a stored value is labelled when no choice holds it. By default, as it is. */
  labelOf?: (value: string) => string;
}
