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

import { useAuthenticatedFetch, type AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";

import { parseCategoryTree, type CategoryNode, type JcrNode } from "./categoryModel";

// The repository path of the category tree's root.
export const CATEGORIES_ROOT = "/Categories";

// The tag closing a category to new submissions, defined as inheritable by the categories module.
const RETIRED_TAG = "retired";

// Thrown when the deletion endpoint refuses to remove a category - something still references it,
// or a deletion guard objected. Either way the deletion is off the table and the UI offers retiring
// instead, carrying the endpoint's own account of what stands in the way.
export class DeletionRefusedError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "DeletionRefusedError";
  }
}

// A refusal from the deletion endpoint, of which the UI needs the sentence describing what blocks
// the deletion, e.g. "This item is referenced in 3 submissions (S-1, S-2, S-3)."
interface DeletionRefusal {
  "status.message"?: string;
}

const DELETION_REFUSED = "Something still refers to this category, so it cannot be deleted.";

// What the endpoint said stands in the way. A body that cannot be read falls back to the general
// sentence rather than to a failure: the deletion was still refused, and saying so with less detail
// beats reporting it as if the server had broken.
const refusalReason = async (response: Response): Promise<string> => {
  try {
    const refusal = await response.json() as DeletionRefusal | null;
    return refusal?.["status.message"] ?? DELETION_REFUSED;
  } catch {
    return DELETION_REFUSED;
  }
};

// The fields of a category that the edit dialog manages. `schemaVersion` is the identifier
// (jcr:uuid) of the bound sch:SchemaVersion; null explicitly removes an existing binding, while
// undefined leaves it untouched.
export interface CategoryFields {
  label: string;
  description?: string;
  schemaVersion?: string | null;
}

const checkOk = (response: Response): Response => {
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Every exchange with the repository goes through here, so that a failure - an unreachable server,
// a refused write, an unreadable response - reaches the UI already worded for the person who will
// read it. A refused deletion is the exception: it is not a failure to report but a refusal the UI
// answers with an offer to retire instead, so it passes through as itself.
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

// All writes go through the standard Sling POST servlet on the affected node itself:
// https://sling.apache.org/documentation/bundles/manipulating-content-the-slingpostservlet-servlets-post.html
// The fetch arrives as an argument because useCategoryTree is the only thing here that can ask the
// hook for one.
const post = (
  authenticatedFetch: AuthenticatedFetch,
  path: string,
  params: Record<string, string>
): Promise<Response> => {
  const body = new URLSearchParams(params);
  // Ask for a JSON (non-redirect) response, so the interesting headers survive
  body.append(":http-equiv-accept", "application/json");
  return reporting(() => authenticatedFetch(path, { method: "POST", body }).then(checkOk));
};

const fieldParams = (fields: CategoryFields): Record<string, string> => {
  const params: Record<string, string> = { label: fields.label };
  if (fields.description !== undefined) {
    params.description = fields.description;
  }
  if (fields.schemaVersion === null) {
    // Explicitly unbind
    params["schemaVersion@Delete"] = "true";
  } else if (fields.schemaVersion !== undefined) {
    params.schemaVersion = fields.schemaVersion;
    // Without the type hint the reference would be stored as a plain string
    params["schemaVersion@TypeHint"] = "Reference";
  }
  return params;
};

// The single owner of all category tree I/O: it holds the parsed tree, and exposes the write
// operations the manager UI needs, each of which re-fetches the whole tree on success - the tree
// is small, and a full refetch guarantees the UI reflects the server-side truth (name mangling,
// ordering, concurrent edits) rather than an optimistic guess.
export function useCategoryTree() {
  const [ tree, setTree ] = useState<CategoryNode[]>([]);
  const [ loading, setLoading ] = useState(true);
  const [ loadError, setLoadError ] = useState<string>();
  const authenticatedFetch = useAuthenticatedFetch();

  // Serialization selectors: `deep` recurses into the tree, the default-enabled `dereference`
  // inlines each bound schema version so it can be displayed without extra requests, and `simple`
  // is what keeps that affordable - without it, inlining a schema version brings its whole
  // requirement subtree and its dereferenced workflow, BPMN and all, to render two words per chip.
  const reload = useCallback((): Promise<void> =>
    reporting(async () => {
      const response = checkOk(await authenticatedFetch(`${CATEGORIES_ROOT}.deep.simple.json`));
      return parseCategoryTree(await response.json() as JcrNode);
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

  // Creates a category under the given parent and returns the new node's path. The node name is
  // derived by the server from the label via :nameHint; the typed fields are then set with a
  // follow-up update, which keeps the type hints in one place.
  const create = useCallback(async (parentPath: string, fields: CategoryFields): Promise<string> => {
    const response = await post(authenticatedFetch, `${parentPath}/`, {
      "jcr:primaryType": "cat:Category",
      ":nameHint": fields.label,
      ...fieldParams(fields),
    });
    const location = response.headers.get("Location") ?? "";
    const newPath = decodeURIComponent(location.replace(/\.json$/, ""));
    await reload();
    return newPath || parentPath;
  }, [authenticatedFetch, reload]);

  // Updates the fields of an existing category.
  const update = useCallback(async (path: string, fields: CategoryFields): Promise<void> => {
    await post(authenticatedFetch, path, fieldParams(fields));
    await reload();
  }, [authenticatedFetch, reload]);

  // Removes a category's schema version binding and touches nothing else, which is what a category
  // about to receive its first subcategory needs: it is ceasing to be a leaf, and only leaves may
  // carry a binding.
  const unbindSchemaVersion = useCallback(async (path: string): Promise<void> => {
    await post(authenticatedFetch, path, { "schemaVersion@Delete": "true" });
    await reload();
  }, [authenticatedFetch, reload]);

  // Moves a category (and its subtree) under a new parent, keeping its name.
  const move = useCallback(async (path: string, newParentPath: string): Promise<void> => {
    await post(authenticatedFetch, path, { ":operation": "move", ":dest": `${newParentPath}/` });
    await reload();
  }, [authenticatedFetch, reload]);

  // Reorders a category among its siblings. `order` uses the Sling :order syntax: "first",
  // "last", "before <siblingName>", or "after <siblingName>".
  const reorder = useCallback(async (path: string, order: string): Promise<void> => {
    await post(authenticatedFetch, path, { ":order": order });
    await reload();
  }, [authenticatedFetch, reload]);

  // Retires or unretires a category by placing or removing the `retired` tag. Sling's @Patch adds and
  // removes individual values of a multi-valued property, which is what a tag write has to be: the
  // property holds every tag the category carries, and rewriting it wholesale would drop the others.
  // TODO Fix this when the workflow engine lands
  const setRetired = useCallback(async (path: string, retired: boolean): Promise<void> => {
    await post(authenticatedFetch, path, {
      "tags@TypeHint": "String[]",
      "tags@Patch": "true",
      tags: `${retired ? "+" : "-"}${RETIRED_TAG}`,
    });
    await reload();
  }, [authenticatedFetch, reload]);

  // Deletes a category through the platform's deletion endpoint rather than a plain repository
  // write: it knows what points at the category, moves what it removes into the archive instead of
  // destroying it, and answers a refusal with the reasons rather than an opaque failure. A refusal
  // rejects with a DeletionRefusedError, which the UI turns into a "retire instead?" offer.
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
    create, update, unbindSchemaVersion, move, reorder, setRetired, remove,
  };
}
