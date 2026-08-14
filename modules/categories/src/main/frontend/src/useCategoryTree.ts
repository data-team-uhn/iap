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

// Thrown when the server refuses to delete a category because submissions (or other content)
// still reference it; the UI offers retiring instead.
export class CategoryReferencedError extends Error {
  constructor() {
    super("This category has submissions and cannot be deleted. It can be retired instead.");
    this.name = "CategoryReferencedError";
  }
}

// The fields of a category that the edit dialog manages. `schemaVersion` is the identifier
// (jcr:uuid) of the bound sch:SchemaVersion; null explicitly removes an existing binding, while
// undefined leaves it untouched.
export interface CategoryFields {
  label: string;
  description?: string;
  schemaVersion?: string | null;
}

const checkOk = (response: Response): Response => {
  if (response.status === 409) {
    throw new CategoryReferencedError();
  }
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Every exchange with the repository goes through here, so that a failure - an unreachable server,
// a refused write, an unreadable response - reaches the UI already worded for the person who will
// read it. A 409 is the exception: it is not a failure to report but a refusal the UI answers with
// an offer to retire instead, so it passes through as itself.
const reporting = async <T>(exchange: () => Promise<T>): Promise<T> => {
  try {
    return await exchange();
  } catch (error: unknown) {
    if (error instanceof CategoryReferencedError) {
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

  // Default serialization selectors: `deep` recurses into the tree, and the default-enabled
  // `dereference` inlines each bound schema version so it can be displayed without extra requests
  const reload = useCallback((): Promise<void> =>
    reporting(async () => {
      const response = checkOk(await authenticatedFetch(`${CATEGORIES_ROOT}.deep.json`));
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

  // Retires or unretires a category.
  const setRetired = useCallback(async (path: string, retired: boolean): Promise<void> => {
    await post(authenticatedFetch, path, { retired: String(retired), "retired@TypeHint": "Boolean" });
    await reload();
  }, [authenticatedFetch, reload]);

  // Deletes a category. Rejects with a CategoryReferencedError when the category has submissions,
  // which the UI turns into a "retire instead?" offer.
  const remove = useCallback(async (path: string): Promise<void> => {
    await post(authenticatedFetch, path, { ":operation": "delete" });
    await reload();
  }, [authenticatedFetch, reload]);

  return {
    tree, loading, loadError, reload,
    create, update, unbindSchemaVersion, move, reorder, setRetired, remove,
  };
}
