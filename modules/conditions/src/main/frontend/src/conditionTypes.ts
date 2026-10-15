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

// Reads the /ConditionTypes catalogue (see docs/conditions.md) into the `ConditionTypes` that the condition model
// and builder take. They hold the comparators, the aggregates and the operand sources. The frontend keeps no list of
// its own.

import { useEffect, useState } from "react";

import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readJson } from "@iap/frontend-commons/requestFailure";

import type {
  Aggregate, Choice, Comparator, ConditionTypes, OperandShape, OperandSource, ValueType,
} from "./conditionModel";

/** Where the catalogue is served. */
export const CATALOGUE_URL = "/ConditionTypes.doc.json";

/** The catalogue entry of an operand source. */
interface SourceEntry {
  name: string;
  label: string;
  valueLabel?: string;
  phrase: string;
  type?: ValueType;
  multiple?: boolean;
  /** The path of an autodoc catalogue whose items are the values to compare with. */
  choices?: string;
  /** The URI template of the list of what the source can name; `{+path}` is the path of the content. */
  candidates?: string;
  offered: boolean;
  order: number;
}

/** One thing a source can name, as its candidates URL lists it, with what an operand naming it holds. */
interface Candidate extends OperandShape {
  value: string;
  label: string;
  /** Other operand values that name it too, e.g. a question's path in its version. */
  aliases?: string[];
  /** A short text telling it apart from another candidate with the same label, e.g. a question's identifier. */
  detail?: string;
}

/** The JSON body a URL answers, or undefined if the request fails or answers an error status. */
async function read<T>(doFetch: AuthenticatedFetch, url: string): Promise<T | undefined> {
  try {
    const response = await doFetch(url);
    return response.ok ? await readJson<T>(response) : undefined;
  } catch {
    return undefined;
  }
}

/** Every item of an autodoc catalogue, in any category, as a choice; an item in several categories comes once. */
const choicesIn = (catalogue?: { items?: Record<string, { name: string; label?: string }[]> }): Choice[] => {
  const items = Object.values(catalogue?.items ?? {}).flat();
  return items.filter((item, at) => items.findIndex(other => other.name === item.name) === at)
    .map(item => ({ value: item.name, label: item.label ?? item.name }));
};

/** The `OperandSource` for a catalogue entry: what it can name at `path`, and what an operand holds and reads as. */
async function sourceOf(doFetch: AuthenticatedFetch, entry: SourceEntry, path: string): Promise<OperandSource> {
  const [ candidates = [], catalogue ] = await Promise.all([
    entry.candidates ? read<Candidate[]>(doFetch, entry.candidates.replace("{+path}", path)) : undefined,
    entry.choices ? read<Parameters<typeof choicesIn>[0]>(doFetch, entry.choices) : undefined,
  ]);
  const choices = choicesIn(catalogue);
  const named = ([ name ]: string[]) => candidates.find(candidate =>
    candidate.value === name || candidate.aliases?.includes(name));
  return {
    name: entry.name,
    label: entry.label,
    valueLabel: entry.valueLabel,
    offered: entry.offered,
    ...entry.candidates ? { candidates: candidates.map(({ value, label, detail }) => ({ value, label, detail })) } : {},
    shape: value => {
      const candidate = named(value);
      return candidate
        ? { type: candidate.type, multiple: candidate.multiple, choices: candidate.choices }
        : { type: entry.type, multiple: entry.multiple, ...choices.length > 0 && { choices } };
    },
    // Before it names anything, an operand reads as the kind of thing it will name, e.g. "its own property"
    describe: value => entry.phrase.replace("{label}",
      named(value)?.label ?? value.at(0) ?? entry.valueLabel?.toLowerCase() ?? ""),
  };
}

/**
 * Reads the catalogue, and what each source can name and be compared with, for the content a condition is on.
 *
 * @param doFetch the fetch to read with
 * @param path the path of the content the condition is on, or of the entity whose conditions are shown
 * @returns the condition types for that content, or undefined if the catalogue cannot be read
 */
export async function loadConditionTypes(doFetch: AuthenticatedFetch, path: string)
  : Promise<ConditionTypes | undefined> {
  const catalogue = await read<{ items?: Record<string, Record<string, unknown>[]> }>(doFetch, CATALOGUE_URL);
  if (!catalogue?.items) {
    return undefined;
  }
  const entries = (category: string) => catalogue.items?.[category] ?? [];
  const sources = (entries("Sources") as unknown as SourceEntry[])
    .filter(entry => entry.name !== "literal")
    .sort((first, second) => first.order - second.order);
  return {
    comparators: entries("Comparators") as unknown as Comparator[],
    aggregates: entries("Aggregators") as unknown as Aggregate[],
    sources: await Promise.all(sources.map(entry => sourceOf(doFetch, entry, path))),
  };
}

/**
 * Loads the condition types for the content at `path`, and again only when `path` changes.
 *
 * @param path the path of the content the condition is on, or of the entity whose conditions are shown
 * @returns the types, undefined while they load or if they cannot be read
 */
export function useConditionTypes(path: string): ConditionTypes | undefined {
  const doFetch = useAuthenticatedFetch();
  const [ types, setTypes ] = useState<ConditionTypes>();
  useEffect(() => {
    let cancelled = false;
    void loadConditionTypes(doFetch, path).then(loaded => {
      if (!cancelled) {
        setTypes(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ doFetch, path ]);
  return types;
}
