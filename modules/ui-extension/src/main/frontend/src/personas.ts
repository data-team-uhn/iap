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

// The persona a user is currently acting as. One person may be entitled to several — the same
// account can file its own submissions and review someone else's — and which one they are wearing
// decides what the UI offers them.
//
// A persona is PRESENTATION ONLY. It never grants or withholds anything: the server authorizes every
// request independently, and a control hidden from a persona must never be the only thing preventing
// the action behind it. What a persona changes is what the UI is *about*, so that "view my
// submissions" looks the same to everyone rather than sprouting administrative affordances for
// whoever happens to hold the rights.
type Persona = string;

// The personas a user can act as, ORDERED LEAST-PERMISSIVE FIRST.
//
// The order is load-bearing, not cosmetic: the active persona defaults to the least permissive one
// available, so that signing in never silently puts someone in a more powerful view than they asked
// for. `availablePersonas()[0]` is that default, which only means anything while this stays sorted.
const PERSONAS: readonly Persona[] = [ "submitter", "reviewer", "administrator" ];

// Human-readable labels, in the source language.
//
// English literals for now, matching the rest of the shell (UserMenu's "Sign out"). Once i18n lands
// these become message lookups; none of them interpolate anything, so they need no formatter.
const PERSONA_LABELS: Record<Persona, string> = {
  submitter: "Submitter",
  reviewer: "Reviewer",
  administrator: "Administrator",
};

// The personas the current user may choose between.
//
// THIS IS THE SEAM. The choice is meant to be constrained by the user's roles — you may only put on
// a hat you are entitled to wear — but IAP has no groups or roles yet, so today everyone may choose
// anything. When roles arrive, this function is the only thing that has to change: everything else
// asks it rather than reading PERSONAS directly.
const availablePersonas = (): Persona[] => [ ...PERSONAS ];

// The label to display for a persona, falling back to the raw value for one we don't know.
const personaLabel = (persona: Persona): string => PERSONA_LABELS[persona] ?? persona;

// --- The store -------------------------------------------------------------------------------
//
// The active persona is held on `window`, deliberately, rather than in a module-level variable or a
// React context.
//
// Every extension is a separate webpack entry point, fetched and eval'd at runtime, so the control
// that SETS the persona and the components that READ it live in different bundles. React itself is
// shared (it is split into the common vendor chunk), but application modules are only deduplicated
// when webpack's heuristics say so — a module this small can legitimately be copied into both
// bundles, and two copies would mean two independent stores, or two context objects that never match.
// Hanging the state off `window` makes that irrelevant: however many copies of this module exist,
// they all read and write the same object.

// The shape stored on the window object.
interface PersonaStore {
  active?: Persona;
}

// The property name under which the store hides on `window`, and the event that announces a change.
const STORE_KEY = "__iapPersona";
const CHANGE_EVENT = "iap:personachange";

// The window-hosted store, created on first use.
const store = (): PersonaStore => {
  const holder = window as unknown as Record<string, PersonaStore | undefined>;
  holder[STORE_KEY] ??= {};
  return holder[STORE_KEY];
};

// The persona the user is currently acting as. Defaults to the least permissive one available, and
// falls back to it if the stored value is no longer on offer.
const getActivePersona = (): Persona => {
  const available = availablePersonas();
  const active = store().active;
  return active !== undefined && available.includes(active) ? active : available[0];
};

// Switches the active persona and notifies every subscriber.
//
// A persona that is not available is ignored rather than throwing: the caller is a UI control, and
// the list it was built from may have gone stale. Refusing quietly keeps the constraint true even
// then — this is the one place the "constrained by roles" rule is enforced.
const setActivePersona = (persona: Persona): void => {
  if (!availablePersonas().includes(persona) || persona === getActivePersona()) {
    return;
  }
  store().active = persona;
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
};

// Subscribes to persona changes; returns the unsubscribe function useSyncExternalStore expects.
const subscribeToPersona = (onChange: () => void): (() => void) => {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
};

// The active persona, re-rendering the component whenever it changes.
const usePersona = (): Persona => useSyncExternalStore(subscribeToPersona, getActivePersona);

export {
  PERSONAS,
  availablePersonas,
  getActivePersona,
  personaLabel,
  setActivePersona,
  subscribeToPersona,
  usePersona,
  type Persona,
};
