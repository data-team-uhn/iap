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

import { useSyncExternalStore } from "react";

// A persona, such as "reviewer": a role a user acts in, one at a time. The same account can file its
// own submissions as a submitter and review someone else's as a reviewer. The UI can show each persona
// different extensions.
//
// A persona never grants or withholds access. The server authorizes every request, whatever the
// persona, and hiding a control from a persona must never be the only thing preventing its action.
type Persona = string;

// The personas, least permissive first. The active persona defaults to the first one available.
const PERSONAS: readonly Persona[] = [ "submitter", "reviewer", "administrator" ];

// The name the UI shows for each persona
const PERSONA_LABELS: Record<Persona, string> = {
  submitter: "Submitter",
  reviewer: "Reviewer",
  administrator: "Administrator",
};

// The personas the current user may choose between, in the order of PERSONAS. Every user may choose
// every persona. The rest of the module asks this rather than reading PERSONAS, so a rule limiting the
// choice belongs here.
const availablePersonas = (): Persona[] => [ ...PERSONAS ];

// The name the UI shows for a persona, or the persona itself if it has no label. `Object.hasOwn` keeps
// an inherited member, such as `constructor`, from answering for a persona.
const personaLabel = (persona: Persona): string =>
  Object.hasOwn(PERSONA_LABELS, persona) ? PERSONA_LABELS[persona] : persona;

// --- The store -------------------------------------------------------------------------------
//
// The active persona is kept on `window`, not in this module. The control that sets it and the
// components that read it are in different bundles, and each bundle may have its own copy of this
// module. Every copy reads and writes the same object on `window`.

// What the store holds
interface PersonaStore {
  active?: Persona;
}

// The property of `window` that holds the store, and the event that announces a change. STORE_KEY is
// exported for code that has to reach the store directly, such as a test resetting it.
const STORE_KEY = "__iapPersona";
const CHANGE_EVENT = "iap:personachange";

// The store, created on first use
const store = (): PersonaStore => {
  const holder = window as unknown as Record<string, PersonaStore | undefined>;
  holder[STORE_KEY] ??= {};
  return holder[STORE_KEY];
};

// The persona the user is acting as: the one chosen, or the first one available if none was chosen or
// the one chosen is no longer available.
const getActivePersona = (): Persona => {
  const available = availablePersonas();
  const active = store().active;
  return active !== undefined && available.includes(active) ? active : available[0];
};

// Makes a persona the active one, and tells every subscriber. A persona that is not available is
// ignored, without an error, since the list a control offers may be out of date.
const setActivePersona = (persona: Persona): void => {
  if (!availablePersonas().includes(persona) || persona === getActivePersona()) {
    return;
  }
  store().active = persona;
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
};

// Calls `onChange` whenever the active persona changes, until the function returned is called
const subscribeToPersona = (onChange: () => void): (() => void) => {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
};

// The active persona, rendering the component again whenever it changes
const usePersona = (): Persona => useSyncExternalStore(subscribeToPersona, getActivePersona);

export {
  PERSONAS,
  STORE_KEY,
  availablePersonas,
  getActivePersona,
  personaLabel,
  setActivePersona,
  subscribeToPersona,
  usePersona,
  type Persona,
};
