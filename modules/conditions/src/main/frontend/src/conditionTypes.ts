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

// What conditions can say on this instance, as the /ConditionTypes catalogue lists it (see docs/conditions.md): the
// comparators and aggregates, and every operand source with what it can name and be compared with, for the content
// a condition is on. Nothing here lists a comparator, an aggregate or a source itself.

import { useEffect, useState } from "react";

import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import type {
  Aggregate, Choice, Comparator, ConditionTypes, OperandShape, OperandSource, ValueType,
} from "./conditionModel";

/** Where the catalogue is served. */
export const CATALOGUE_URL = "/ConditionTypes.doc.json";

/** A source as the catalogue lists it. */
interface SourceEntry {
  name: string;
  label: string;
  valueLabel?: string;
  phrase: string;
  type?: ValueType;
  multiple?: boolean;
  /** The path of an autodoc catalogue whose items are the values to compare with. */
  choices?: string;
  /** A URI template, `{+path}` standing for the content the condition is on, listing what the source can name. */
  candidates?: string;
  offered: boolean;
  order: number;
}

/** Something a source can name, and what an operand naming it then holds. */
interface Candidate extends OperandShape {
  value: string;
  label: string;
  /** Other names an operand may give it, e.g. a question's path in its version. */
  aliases?: string[];
  /** What tells it apart from another called the same, e.g. a question's identifier. */
  detail?: string;
}

/** What a request answers as JSON, or nothing if it fails. */
async function read<T>(doFetch: AuthenticatedFetch, url: string): Promise<T | undefined> {
  try {
    const response = await doFetch(url);
    return response.ok ? await response.json() as T : undefined;
  } catch {
    return undefined;
  }
}

/** Every item of an autodoc catalogue, as a value to pick, in any category. */
const choicesIn = (catalogue?: { items?: Record<string, { name: string; label?: string }[]> }): Choice[] => {
  const items = Object.values(catalogue?.items ?? {}).flat();
  return items.filter((item, at) => items.findIndex(other => other.name === item.name) === at)
    .map(item => ({ value: item.name, label: item.label ?? item.name }));
};

/** A source ready for the builder: what it can name here, and how each operand reads and what it holds. */
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
 * @param path the content the condition is on, or the entity holding the conditions to show
 * @returns what conditions can say there, or undefined if the catalogue cannot be read
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
 * What conditions can say on the given content, read once for it.
 *
 * @param path the content the condition is on, or the entity holding the conditions to show
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
