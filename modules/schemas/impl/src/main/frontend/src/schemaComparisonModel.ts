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

import { diffArrays } from "diff";

import { compareFields, type FieldDifference } from "@iap/frontend-commons/diff/contentDiffModel";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

import { nameOf } from "./schemaModel";
import { headingOf, optionLabelOf, optionsOf, partsOf, resourceTypeOf } from "./schemaVersionTreeModel";

// What changed between two versions of a schema, as one outline of the newer version with what the older one held
// put back where it was. No React, no fetch.

export interface NamedField {
  name: string;
  label: string;
}

// A condition as compared, whatever the words of what it refers to, and as said where it can be
export interface ConditionSaid {
  key: string;
  words?: string;
}

export interface ComparisonSettings {
  // The fields compared, by the names they are edited under
  versionFields: NamedField[];
  partFields: NamedField[];
  optionFields: NamedField[];
  // A part's condition, in the version it is in
  conditionOf: {
    before: (part: SerializedNode) => ConditionSaid | undefined;
    after: (part: SerializedNode) => ConditionSaid | undefined;
  };
}

export type Change = "added" | "removed" | "changed" | "unchanged";

export interface LabelledDifference extends FieldDifference {
  label: string;
}

export interface OptionComparison {
  name: string;
  label: string;
  change: Change;
  // Placed elsewhere among the options both versions have
  reordered: boolean;
  fields: LabelledDifference[];
}

export interface PartComparison {
  name: string;
  type: string;
  heading: string;
  change: Change;
  // What held it before, when it moved from elsewhere: null for the top level of the version
  movedFrom?: string | null;
  // Placed elsewhere among the same siblings
  reordered: boolean;
  fields: LabelledDifference[];
  // When it applies, as said in each version, where that changed
  condition?: { before?: string; after?: string };
  options: OptionComparison[];
  parts: PartComparison[];
}

export interface ComparisonSummary {
  added: number;
  removed: number;
  changed: number;
  moved: number;
}

export interface VersionComparison {
  // What the version says of itself that changed
  version: LabelledDifference[];
  parts: PartComparison[];
  summary: ComparisonSummary;
}

interface Placed {
  node: SerializedNode;
  parent: SerializedNode;
  path: string;
}

const placesOf = (holder: SerializedNode, path = ""): Placed[] => partsOf(holder).flatMap(part => {
  const at = `${path}/${nameOf(part)}`;
  return [ { node: part, parent: holder, path: at }, ...placesOf(part, at) ];
});

// Which of the names both orders hold kept their order: the longest sequence they share
export const keptInOrder = (before: string[], after: string[]): Set<string> =>
  new Set(diffArrays(before, after).filter(chunk => !chunk.added && !chunk.removed).flatMap(chunk => chunk.value));

// What a list compared in its new order becomes once what was removed is put back, each after the last of what
// preceded it before
function withRemoved<T, K>(listed: { item: T; old?: K }[], before: K[], gone: (old: K) => boolean,
  removed: (old: K) => T): T[] {
  const merged = [ ...listed ];
  before.forEach((old, index) => {
    if (!gone(old)) {
      return;
    }
    const preceding = new Set(before.slice(0, index));
    const after = merged.reduce((last, entry, at) => (entry.old !== undefined && preceding.has(entry.old) ? at : last),
      -1);
    merged.splice(after + 1, 0, { item: removed(old), old });
  });
  return merged.map(entry => entry.item);
}

// The fields that differ between two snapshots, each by the name it is edited under
const differencesOf = (old: SerializedNode, node: SerializedNode, fields: NamedField[]): LabelledDifference[] =>
  fields.flatMap(field => compareFields(old, node, [ field.name ])
    .map(difference => ({ ...difference, label: field.label })));

const summarized = (parts: PartComparison[], summary: ComparisonSummary): ComparisonSummary => parts.reduce(
  (counts, part) => summarized(part.parts, {
    added: counts.added + (part.change === "added" ? 1 : 0),
    removed: counts.removed + (part.change === "removed" ? 1 : 0),
    changed: counts.changed + (part.change === "changed" ? 1 : 0),
    moved: counts.moved + (part.movedFrom !== undefined || part.reordered ? 1 : 0),
  }), summary);

// Two versions compared. A part is matched by where its identifier puts it; or else by its identifier among what its
// matched holder held, which then moved with it; or else by its identifier alone where only one part of its type still
// unmatched goes by it in each version, which then moved. One of another type is another part. An option is matched by
// its name among its question's, so that a value edited shows as one.
export function compareVersions(before: SerializedNode, after: SerializedNode,
  settings: ComparisonSettings): VersionComparison {
  const olds = placesOf(before);
  const news = placesOf(after);
  const matched = new Map<SerializedNode, Placed>();
  const taken = new Set<SerializedNode>();
  const match = (node: SerializedNode, old: Placed) => {
    matched.set(node, old);
    taken.add(old.node);
  };
  const byPath = new Map(olds.map(place => [ place.path, place ]));
  news.forEach(place => {
    const old = byPath.get(place.path);
    if (old && resourceTypeOf(old.node) === resourceTypeOf(place.node)) {
      match(place.node, old);
    }
  });
  const key = (place: Placed) => `${resourceTypeOf(place.node)} ${nameOf(place.node)}`;
  // Holders before what they hold, so that what a moved part holds is looked for among what it held
  news.filter(place => !matched.has(place.node)).forEach(place => {
    const candidates = olds.filter(old => !taken.has(old.node) && key(old) === key(place));
    const held = candidates.find(old => old.parent === matched.get(place.parent)?.node);
    const alone = candidates.length === 1
      && news.filter(other => !matched.has(other.node) && key(other) === key(place)).length === 1;
    const counterpart = held ?? (alone ? candidates[0] : undefined);
    if (counterpart) {
      match(place.node, counterpart);
    }
  });

  const optionOf = (change: Change, option: SerializedNode, fields: LabelledDifference[] = [], reordered = false) =>
    ({ name: nameOf(option), label: optionLabelOf(option), change, reordered, fields });
  const compareOptions = (question?: SerializedNode, old?: SerializedNode): OptionComparison[] => {
    const previous = old ? optionsOf(old) : [];
    const current = question ? optionsOf(question) : [];
    const oldByName = new Map(previous.map(option => [ nameOf(option), option ]));
    const names = new Set(current.map(nameOf));
    const kept = keptInOrder(previous.map(nameOf).filter(name => names.has(name)),
      current.map(nameOf).filter(name => oldByName.has(name)));
    const listed = current.map(option => {
      const counterpart = oldByName.get(nameOf(option));
      if (!counterpart) {
        return { item: optionOf("added", option) };
      }
      const fields = differencesOf(counterpart, option, settings.optionFields);
      return {
        item: optionOf(fields.length > 0 ? "changed" : "unchanged", option, fields, !kept.has(nameOf(option))),
        old: counterpart,
      };
    });
    return withRemoved(listed, previous, option => !names.has(nameOf(option)), option => optionOf("removed", option));
  };

  const part = (node: SerializedNode, change: Change, parts: PartComparison[],
    options: OptionComparison[]): PartComparison =>
    ({ name: nameOf(node), type: resourceTypeOf(node), heading: headingOf(node), change, reordered: false,
      fields: [], options, parts });
  // What an added or removed part says, but for its heading, which is shown as such
  const saidOf = (node: SerializedNode, side: "before" | "after") => {
    const others = side === "before" ? [ node, {} ] : [ {}, node ];
    const said = settings.conditionOf[side](node);
    return {
      fields: differencesOf(others[0], others[1], settings.partFields)
        .filter(difference => difference[side] !== headingOf(node)),
      ...said?.words === undefined ? {} : { condition: { [side]: said.words } },
    };
  };
  const removedPart = (node: SerializedNode): PartComparison => ({
    ...part(node, "removed", partsOf(node).filter(child => !taken.has(child)).map(removedPart),
      compareOptions(undefined, node)),
    ...saidOf(node, "before"),
  });
  const holderName = (holder: SerializedNode) => (holder === before ? null : headingOf(holder));

  // What a part of the newer version holds, compared with what its counterpart held, if it has one
  const compareChildren = (holder: SerializedNode, oldHolder?: SerializedNode): PartComparison[] => {
    const listed = partsOf(holder).map(child => {
      const old = matched.get(child);
      return old ? { item: comparePart(child, old, oldHolder), old: old.node } : { item: addedPart(child) };
    });
    if (!oldHolder) {
      return listed.map(entry => entry.item);
    }
    const stayed = listed.filter(entry => entry.item.movedFrom === undefined && entry.old !== undefined);
    const kept = keptInOrder(partsOf(oldHolder).filter(child => stayed.some(entry => entry.old === child)).map(nameOf),
      stayed.map(entry => entry.item.name));
    stayed.forEach(entry => {
      entry.item.reordered = !kept.has(entry.item.name);
    });
    return withRemoved(listed, partsOf(oldHolder), child => !taken.has(child), removedPart);
  };
  const addedPart = (node: SerializedNode): PartComparison =>
    ({ ...part(node, "added", compareChildren(node), compareOptions(node)), ...saidOf(node, "after") });
  const comparePart = (node: SerializedNode, old: Placed, oldHolder?: SerializedNode): PartComparison => {
    const fields = differencesOf(old.node, node, settings.partFields);
    const [ was, is ] = [ settings.conditionOf.before(old.node), settings.conditionOf.after(node) ];
    const condition = was?.key === is?.key ? undefined : { before: was?.words, after: is?.words };
    const options = compareOptions(node, old.node);
    const changed = fields.length > 0 || condition !== undefined
      || options.some(option => option.change !== "unchanged" || option.reordered);
    return {
      ...part(node, changed ? "changed" : "unchanged", compareChildren(node, old.node), options),
      ...old.parent === oldHolder ? {} : { movedFrom: holderName(old.parent) },
      fields,
      ...condition ? { condition } : {},
    };
  };

  const parts = compareChildren(after, before);
  return {
    version: differencesOf(before, after, settings.versionFields),
    parts,
    summary: summarized(parts, { added: 0, removed: 0, changed: 0, moved: 0 }),
  };
}
