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
import { describeRequestFailure, messageOf, readJson, RequestError } from "@iap/frontend-commons/requestFailure";

import { parseCategoryTree, type CategoryNode, type JcrNode } from "./categoryModel";

// The repository path of the category tree's root.
export const CATEGORIES_ROOT = "/Categories";

// The tag that closes a category, and everything under it, to new submissions.
const RETIRED_TAG = "retired";

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

// The fields of a category that a save writes. `schemaVersion` is the `jcr:uuid` of the bound
// sch:SchemaVersion: null removes the binding, and undefined leaves it as it is.
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

// Writes to a node through the Sling POST servlet:
// https://sling.apache.org/documentation/bundles/manipulating-content-the-slingpostservlet-servlets-post.html
// `authenticatedFetch` is an argument because useAuthenticatedFetch is a hook, which only a hook or a
// component can call.
const post = (
  authenticatedFetch: AuthenticatedFetch,
  path: string,
  params: Record<string, string>
): Promise<Response> => {
  const body = new URLSearchParams(params);
  // Ask for the JSON status response rather than the HTML one
  body.append(":http-equiv-accept", "application/json");
  return reporting(() => authenticatedFetch(path, { method: "POST", body }).then(checkOk));
};

const fieldParams = (fields: CategoryFields): Record<string, string> => {
  const params: Record<string, string> = { label: fields.label };
  if (fields.description !== undefined) {
    params.description = fields.description;
  }
  if (fields.schemaVersion === null) {
    params["schemaVersion@Delete"] = "true";
  } else if (fields.schemaVersion !== undefined) {
    params.schemaVersion = fields.schemaVersion;
    // Without the type hint the reference would be stored as a plain string
    params["schemaVersion@TypeHint"] = "Reference";
  }
  return params;
};

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

  // Creates a category under the given parent, and resolves to its path. The server derives the
  // node name from the label, through `:nameHint`. If the server reports no Location, it resolves to
  // the parent's path.
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

  // Removes a category's schema version binding, and leaves its other fields alone.
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

  // Retires or unretires a category by adding or removing the `retired` tag. `@Patch` changes that
  // one value: `tags` holds every tag the category carries, and assigning it would drop the others.
  // TODO Fix this when the workflow engine lands
  const setRetired = useCallback(async (path: string, retired: boolean): Promise<void> => {
    await post(authenticatedFetch, path, {
      "tags@TypeHint": "String[]",
      "tags@Patch": "true",
      tags: `${retired ? "+" : "-"}${RETIRED_TAG}`,
    });
    await reload();
  }, [authenticatedFetch, reload]);

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
    create, update, unbindSchemaVersion, move, reorder, setRetired, remove,
  };
}
