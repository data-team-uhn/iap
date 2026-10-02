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

import { whenApplies } from "@iap/conditions/conditionModel";
import { useTagChoices } from "@iap/conditions/useTagChoices";
import { candidateOf, referenceQuery } from "@iap/frontend-commons/fields/fieldsModel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf, RequestError } from "@iap/frontend-commons/requestFailure";
import { type SerializedNode } from "@iap/frontend-commons/serializedNode";

import { type ComparedField, fieldsOfDefinitions } from "./comparisonFields";
import { conditionKeyOf, schemaSources } from "./conditionModel";
import { compareVersions, type ConditionSaid, type VersionComparison } from "./schemaComparisonModel";
import { type JcrNode, pathOf } from "./schemaModel";
import { conditionOf, indexQuestions } from "./schemaVersionTreeModel";
import { readNode, useNode } from "./useNode";
import { useOptionsFrom } from "./useOptionsFrom";

const asNode = (node: JcrNode): JcrNode => node;

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

// The fields compared, each list read from the definitions named for it, each definition read once, and a definition
// that cannot be read named in what is said about it. What describes fields is content, which does not change while
// the page is open.
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
          // Not a version's label, which tells versions apart, and says which is which in the title
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

// What the targets of the reference fields a comparison shows are called, by their paths and identifiers
export function useReferenceNames(fields: ComparedField[]) {
  const doFetch = useAuthenticatedFetch();
  const [ names, setNames ] = useState<Record<string, string>>({});
  const queries = JSON.stringify(fields.filter(field => field.referenceType !== undefined)
    .map(field => [ referenceQuery(field), field.referenceRoot ]));
  useEffect(() => {
    void Promise.all((JSON.parse(queries) as [ string, string | undefined ][]).map(async ([ query, root ]) => {
      const response = await doFetch(`/search.json?${new URLSearchParams({ query, limit: "1000" }).toString()}`);
      if (!response.ok) {
        throw new RequestError(response.status);
      }
      return ((await response.json()) as { rows: SerializedNode[] }).rows.flatMap((row): [ string, string ][] => {
        const candidate = candidateOf(row, root);
        return candidate ? [ [ candidate.path, candidate.label ], [ String(row["jcr:uuid"]), candidate.label ] ] : [];
      });
    }))
      .then(found => setNames(Object.fromEntries(found.flat())))
      // Unnamed, references show as where they point
      .catch(() => setNames({}));
  }, [ doFetch, queries ]);
  return names;
}

// Two versions of a schema, by their names, compared on the given fields once they are known
export function useVersionComparison(schemaPath: string, names: [ string, string ], fields?: ComparedFields) {
  const before = useNode(`${schemaPath}/${names[0]}`, "deep.-dereference", asNode);
  const after = useNode(`${schemaPath}/${names[1]}`, "deep.-dereference", asNode);
  const indexBefore = useMemo(() => indexQuestions(before.value ?? {}), [ before.value ]);
  const indexAfter = useMemo(() => indexQuestions(after.value ?? {}), [ after.value ]);
  // Read once for both versions: the tags conditions name, and the items questions take their options from
  const tags = useTagChoices();
  const items = useOptionsFrom(useMemo(() => ({ ...indexAfter,
    questions: [ ...indexBefore.questions, ...indexAfter.questions ] }), [ indexBefore, indexAfter ]));
  const comparison = useMemo((): VersionComparison | undefined => {
    if (!fields || !before.value || !after.value) {
      return undefined;
    }
    const saidIn = (version: JcrNode, index: typeof indexAfter) => {
      const sources = schemaSources(index, tags, items);
      return (part: JcrNode): ConditionSaid | undefined => {
        const condition = conditionOf(part);
        return condition && {
          key: conditionKeyOf(condition, index, pathOf(version)),
          words: whenApplies(condition, sources),
        };
      };
    };
    return compareVersions(before.value, after.value, {
      versionFields: fields.version, partFields: fields.part, optionFields: fields.option,
      conditionOf: { before: saidIn(before.value, indexBefore), after: saidIn(after.value, indexAfter) },
    });
  }, [ before.value, after.value, fields, tags, items, indexBefore, indexAfter ]);
  return {
    comparison,
    loading: before.loading || after.loading,
    loadError: before.loadError ?? after.loadError,
    reload: async () => {
      await Promise.all([ before.reload(), after.reload() ]);
    },
  };
}
