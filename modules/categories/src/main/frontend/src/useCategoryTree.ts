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

import { useCallback, useEffect, useState } from "react";

import type { PatchValue } from "@iap/frontend-commons/fields/fieldsModel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, readJson, RequestError } from "@iap/frontend-commons/requestFailure";
import { patch, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { parseCategoryTree, type CategoryNode, type JcrNode } from "./categoryModel";

// The repository path of the category tree's root.
export const CATEGORIES_ROOT = "/Categories";

// The deletion endpoint's refusal to remove a category, because something references it or a
// deletion guard objected. The message is the endpoint's own sentence saying why.
export class DeletionRefusedError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "DeletionRefusedError";
  }
}

// The body of a refusal from the deletion endpoint. `status.message` says what blocks the deletion,
// such as "This item is referenced in 3 submissions (S-1, S-2, S-3)."
interface DeletionRefusal {
  "status.message"?: string;
}

const DELETION_REFUSED = "Something still refers to this category, so it cannot be deleted.";

// The endpoint's reason for refusing. An unreadable body gives the general sentence instead, since
// the deletion was refused all the same.
const refusalReason = async (response: Response): Promise<string> => {
  try {
    const refusal = await response.json() as DeletionRefusal | null;
    return refusal?.["status.message"] ?? DELETION_REFUSED;
  } catch {
    return DELETION_REFUSED;
  }
};

// The fields of a category that a save writes. `schemaVersion` is the path of the bound
// sch:SchemaVersion: null removes the binding, and undefined leaves it as it is.
export interface CategoryFields {
  label: string;
  description?: string;
  schemaVersion?: string | null;
}

// Where a move puts a category: under `parent`, or under its own parent when none is given, and
// before the sibling named `before`, or else last.
export interface Placement {
  parent?: string;
  before?: string;
}

const checkOk = (response: Response): Response => {
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Runs one exchange with the repository. A failure, such as an unreachable server, a refused write
// or an unreadable response, becomes a sentence for the person using the UI. A DeletionRefusedError
// passes through unchanged, so that a refusal stays apart from a failure.
const reporting = async <T>(exchange: () => Promise<T>): Promise<T> => {
  try {
    return await exchange();
  } catch (error: unknown) {
    if (error instanceof DeletionRefusedError) {
      throw error;
    }
    throw new Error(describeRequestFailure(error));
  }
};

// The fields a save changes, as an update event's patch.
const changesOf = (fields: CategoryFields): Record<string, PatchValue> => ({
  label: fields.label,
  ...fields.description !== undefined && { description: fields.description },
  ...fields.schemaVersion !== undefined && { schemaVersion: fields.schemaVersion },
});

// A move event's parameters: those the placement gives.
const moveParams = ({ parent, before }: Placement): Record<string, string> => ({
  ...parent && { parent },
  ...before && { before },
});

// Reads and writes the category tree. It holds the parsed tree, and every successful write reloads
// all of it. The tree then shows what the server made of the write, such as the node name it chose,
// along with other people's edits.
export function useCategoryTree() {
  const [ tree, setTree ] = useState<CategoryNode[]>([]);
  const [ loading, setLoading ] = useState(true);
  const [ loadError, setLoadError ] = useState<string>();
  const authenticatedFetch = useAuthenticatedFetch();

  // `deep` reads the whole tree, and the default `dereference` inlines each bound schema version.
  // `simple` keeps an inlined version to its own properties, without its requirements and the
  // workflow it references.
  const reload = useCallback((): Promise<void> =>
    reporting(async () => {
      const response = checkOk(await authenticatedFetch(`${CATEGORIES_ROOT}.deep.simple.json`));
      return parseCategoryTree(await readJson<JcrNode>(response));
    })
      .then(nodes => {
        setTree(nodes);
        setLoadError(undefined);
      })
      .catch((error: unknown) => {
        setLoadError(messageOf(error));
      })
      .finally(() => setLoading(false)), [authenticatedFetch]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Sends a workflow event to a category, or to the tree's root, then reads the tree again.
  const send = useCallback(async (path: string, event: string, params?: Record<string, string>) => {
    await sendEvent(authenticatedFetch, path, event, params);
    await reload();
  }, [authenticatedFetch, reload]);

  // Creates a category under the given parent. The server names it after its label.
  const create = useCallback((parentPath: string, fields: CategoryFields): Promise<void> =>
    send(parentPath, "create", { type: "cat:Category", ...patch(changesOf(fields)) }), [send]);

  // Updates the fields of an existing category.
  const update = useCallback((path: string, fields: CategoryFields): Promise<void> =>
    send(path, "update", patch(changesOf(fields))), [send]);

  // Removes a category's schema version binding, and leaves its other fields alone.
  const unbindSchemaVersion = useCallback((path: string): Promise<void> =>
    send(path, "update", patch({ schemaVersion: null })), [send]);

  // Moves a category, with its subtree, to another parent or to another place among its siblings.
  const move = useCallback((path: string, placement: Placement): Promise<void> =>
    send(path, "move", moveParams(placement)), [send]);

  // Closes a category, and everything under it, to new submissions.
  const retire = useCallback((path: string): Promise<void> => send(path, "retire"), [send]);

  // Opens a category retired by itself to new submissions again.
  const reopen = useCallback((path: string): Promise<void> => send(path, "activate"), [send]);

  // Deletes a category with a DELETE to the deletion endpoint, which moves it into the archive. A
  // `:operation=delete` POST would skip the endpoint's check for references. A refusal rejects with
  // a DeletionRefusedError.
  const remove = useCallback(async (path: string): Promise<void> => {
    await reporting(async () => {
      const response = await authenticatedFetch(path, { method: "DELETE" });
      if (response.status === 409) {
        throw new DeletionRefusedError(await refusalReason(response));
      }
      return checkOk(response);
    });
    await reload();
  }, [authenticatedFetch, reload]);

  return {
    tree, loading, loadError, reload,
    create, update, unbindSchemaVersion, move, retire, reopen, remove,
  };
}
