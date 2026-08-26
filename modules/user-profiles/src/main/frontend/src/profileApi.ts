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

import { PERSONAS, personaLabel } from "@iap/ui-extension/personas";

// The profile API: one document describing an account, assembled per request from the field
// catalogue and what the requester is allowed to see of it. Reading and writing both go through
// `/system/iap/profile`, which answers about the signed-in person when no account is named.
const PROFILE_URL = "/system/iap/profile.json";
const SAVE_URL = "/system/iap/profile";

// One field, as the API describes it: the catalogue's definition and this requester's verdict on it
// in the same object, so a control can be rendered without a second request and without the client
// re-deriving any of the rules.
export interface ProfileField {
  name: string;
  label: string;
  description?: string;
  category?: string[];
  // Whether this describes the person or says how they want the application to behave
  kind: "profile" | "preference";
  dataType: string;
  required: boolean;
  multiple: boolean;
  usable: boolean;
  // Present only when the catalogue closes the field to a set of choices
  allowedValues?: string[];
  pattern?: string;
  order?: number;
  // What this requester may do, and where the value came from
  readable: boolean;
  editable: boolean;
  provenance: "idp" | "platform" | "local" | "unset";
  // Absent when the field is not readable — a withheld value is described as if nothing were recorded
  values?: string[];
  // Present only for a definition the instance cannot honour, e.g. an unknown data type
  problems?: string[];
}

export interface Profile {
  account: string;
  // Whether the account is managed by an identity provider, which is what makes the fields it
  // supplies read-only however the catalogue describes them
  external: boolean;
  idp: string;
  principals: string[];
  fields: ProfileField[];
}

// What came of a save. A refusal about the person rather than about any one field is keyed by no
// field name, which is why `forbidden` is reported separately: a form filing every reason against a
// control would drop that one silently.
export interface SaveOutcome {
  status: "success" | "error";
  forbidden: boolean;
  changed: string[];
  refused: Record<string, string>;
}

type Fetcher = (url: string, init?: RequestInit) => Promise<Response>;

// The signed-in person's profile.
export async function readProfile(fetcher: Fetcher): Promise<Profile> {
  const response = await fetcher(PROFILE_URL);
  if (!response.ok) {
    throw new Error(`Could not load your profile: ${response.status}`);
  }
  return await response.json() as Profile;
}

// Records new values, as a form post so that the servlet reads them as ordinary request parameters.
// Writes are all-or-nothing: a profile half saved is worse to hand back than one turned down, so a
// refusal leaves everything as it was and says why, per field.
export async function saveProfile(fetcher: Fetcher, values: Record<string, string>): Promise<SaveOutcome> {
  const body = new URLSearchParams();
  Object.entries(values).forEach(([ field, value ]) => body.append(field, value));
  const response = await fetcher(SAVE_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: body.toString(),
  });
  // 400 and 403 both carry an outcome describing what went wrong; only a genuinely broken response
  // has nothing to report
  const outcome = await response.json().catch(() => null) as SaveOutcome | null;
  if (!outcome) {
    throw new Error(`Could not save your profile: ${response.status}`);
  }
  return outcome;
}

// The single value a control edits. The API models every field as a list because a definition may
// allow several, but nothing in the shipped catalogue does, so the form works one value at a time
// and leaves the rest untouched rather than pretending to edit them.
export const currentValue = (field: ProfileField): string => field.values?.[0] ?? "";

// Turns a stored choice into something worth reading. Personas borrow the switcher's own labels so
// that the same hat is not called two different things in two places; languages are named in
// themselves, the way a language picker should read; anything else is title-cased, which is enough
// for the vocabularies the catalogue ships.
export function choiceLabel(field: ProfileField, value: string): string {
  // The guard is about meaning, not types: personaLabel hands back an unknown value unchanged, and a
  // persona this platform does not define reads better title-cased than raw.
  if (field.name === "persona" && PERSONAS.includes(value)) {
    return personaLabel(value);
  }
  if (field.name === "locale") {
    return languageName(value);
  }
  return value.charAt(0).toUpperCase() + value.slice(1);
}

// A language's name in that language ("Français"), falling back to the tag itself where the runtime
// cannot name it.
function languageName(tag: string): string {
  try {
    // `fallback: "none"` so an unnameable tag comes back undefined and is shown as itself, rather
    // than Intl quietly handing back the code as though it had named it
    return new Intl.DisplayNames([ tag ], { type: "language", fallback: "none" }).of(tag) ?? tag;
  } catch {
    return tag;
  }
}

// Why a field cannot be edited, in terms of the person rather than of the rules. Only ever asked
// about a field that is readable but not editable, so "you may not" is never the answer here.
export function readOnlyReason(field: ProfileField, profile: Profile): string {
  if (field.problems?.length) {
    return "This field is not configured correctly, so it cannot be changed.";
  }
  if (field.provenance === "idp") {
    return `Provided by ${profile.idp || "your identity provider"}; change it there.`;
  }
  if (field.provenance === "platform") {
    return "Maintained by the platform.";
  }
  return "Managed by an administrator.";
}
