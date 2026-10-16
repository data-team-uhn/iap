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

import { useEffect, useMemo, useState } from "react";

import { type ConditionTypes, whenApplies } from "@iap/conditions/conditionModel";
import { useConditionTypes } from "@iap/conditions/conditionTypes";
import type { ReferenceCandidate } from "@iap/frontend-commons/fields/fieldsModel";
import { readCandidates } from "@iap/frontend-commons/fields/referenceCandidates";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { readNode, useNode } from "@iap/frontend-commons/useNode";

import { type ComparedField, fieldsOfDefinitions } from "./comparisonFields";
import { conditionKeyOf } from "./conditionModel";
import { compareVersions, type ConditionSaid, type VersionComparison } from "./schemaComparisonModel";
import { pathOf } from "./schemaModel";
import { conditionOf, indexQuestions } from "./schemaVersionTreeModel";

const asNode = (node: SerializedNode): SerializedNode => node;

// The workflow definitions describing the fields compared, for a version, its parts and their options
export interface ComparedFieldSources {
  version: string[];
  part: string[];
  option: string[];
}

export interface ComparedFields {
  version: ComparedField[];
  part: ComparedField[];
  option: ComparedField[];
}

// Reads the fields compared. Each list comes from the definitions named for it, and each definition is read once.
// When a definition cannot be read, the error names it. The definitions are content, unchanged while the page is open.
export function useComparedFields(sources: ComparedFieldSources) {
  const [ fields, setFields ] = useState<ComparedFields>();
  const [ loadError, setLoadError ] = useState<string>();
  // Counts the reads asked for, so that asking again reads again
  const [ attempts, setAttempts ] = useState(0);
  const doFetch = useAuthenticatedFetch();
  const named = JSON.stringify(sources);
  useEffect(() => {
    const wanted: ComparedFieldSources = JSON.parse(named) as ComparedFieldSources;
    const paths = [ ...new Set([ ...wanted.version, ...wanted.part, ...wanted.option ]) ];
    Promise.all(paths.map(path => readNode(doFetch, path, "deep.-dereference").catch((error: unknown) => {
      throw new Error(`${path} could not be read. ${messageOf(error)}`);
    })))
      .then(read => {
        const fieldsOf = (kind: string[]) =>
          fieldsOfDefinitions(read.filter((_definition, at) => kind.includes(paths[at])));
        setFields({
          // Leaves out the version's label. It differs between any two versions, and the title says which is which.
          version: fieldsOf(wanted.version).filter(field => field.name !== "version"),
          part: fieldsOf(wanted.part),
          option: fieldsOf(wanted.option),
        });
        setLoadError(undefined);
      })
      .catch((error: unknown) => setLoadError(messageOf(error)));
  }, [ doFetch, named, attempts ]);
  const reload = () => {
    setAttempts(count => count + 1);
    return Promise.resolve();
  };
  return { fields, loadError, reload };
}

// A target's label, keyed by its path and by its identifier, either of which a reference may store
const namesOf = ({ path, label, identifier }: ReferenceCandidate): [ string, string ][] =>
  [ path, ...identifier ? [ identifier ] : [] ].map(name => [ name, label ]);

// The names of what the reference fields of a comparison point at, by their paths and identifiers
export function useReferenceNames(fields: ComparedField[]) {
  const doFetch = useAuthenticatedFetch();
  const [ names, setNames ] = useState<Record<string, string>>({});
  const targets = JSON.stringify(fields.filter(field => field.referenceType !== undefined)
    .map(({ referenceType, referenceRoot }) => ({ referenceType, referenceRoot })));
  useEffect(() => {
    void Promise.all((JSON.parse(targets) as Pick<ComparedField, "referenceType" | "referenceRoot">[])
      .map(target => readCandidates(doFetch, target)))
      .then(found => setNames(Object.fromEntries(found.flat().flatMap(namesOf))))
      // Without names, references show as where they point
      .catch(() => setNames({}));
  }, [ doFetch, targets ]);
  return names;
}

// Two versions of a schema, by their names, compared on the given fields once they are known
export function useVersionComparison(schemaPath: string, names: [ string, string ], fields?: ComparedFields) {
  const before = useNode(`${schemaPath}/${names[0]}`, "deep.-dereference.order", asNode);
  const after = useNode(`${schemaPath}/${names[1]}`, "deep.-dereference.order", asNode);
  const indexBefore = useMemo(() => indexQuestions(before.value ?? {}), [ before.value ]);
  const indexAfter = useMemo(() => indexQuestions(after.value ?? {}), [ after.value ]);
  // What each version's conditions can say, to put them into words
  const typesBefore = useConditionTypes(`${schemaPath}/${names[0]}`);
  const typesAfter = useConditionTypes(`${schemaPath}/${names[1]}`);
  const comparison = useMemo((): VersionComparison | undefined => {
    if (!fields || !before.value || !after.value) {
      return undefined;
    }
    const saidIn = (version: SerializedNode, index: typeof indexAfter, types?: ConditionTypes) =>
      (part: SerializedNode): ConditionSaid | undefined => {
        const condition = conditionOf(part);
        return condition && {
          key: conditionKeyOf(condition, index, pathOf(version)),
          words: types && whenApplies(condition, types),
        };
      };
    return compareVersions(before.value, after.value, {
      versionFields: fields.version, partFields: fields.part, optionFields: fields.option,
      conditionOf: {
        before: saidIn(before.value, indexBefore, typesBefore), after: saidIn(after.value, indexAfter, typesAfter),
      },
    });
  }, [ before.value, after.value, fields, typesBefore, typesAfter, indexBefore, indexAfter ]);
  return {
    comparison,
    loading: before.loading || after.loading,
    loadError: before.loadError ?? after.loadError,
    reload: async () => {
      await Promise.all([ before.reload(), after.reload() ]);
    },
  };
}
