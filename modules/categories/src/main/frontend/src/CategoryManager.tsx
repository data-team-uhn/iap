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

import { useState } from "react";

import AddIcon from "@mui/icons-material/Add";
import { Button, Typography } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import ErrorDialog from "@iap/frontend-commons/components/ErrorDialog";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";

import CategoryDialog, { type CategorySubmission } from "./CategoryDialog";
import CategoryTree, { type CategoryActions } from "./CategoryTree";
import DeleteCategoryDialog from "./DeleteCategoryDialog";
import { CATEGORIES_ROOT, useCategoryTree } from "./useCategoryTree";

import type { CategoryNode } from "./categoryModel";

// The state of the create/edit dialog: which category is being edited, or which parent a new one
// is being created under.
interface DialogState {
  mode: "create" | "edit";
  node?: CategoryNode;
  parentPath: string;
}

const parentPathOf = (path: string): string => path.slice(0, path.lastIndexOf("/")) || CATEGORIES_ROOT;

// The "Submission categories" administrative tool: displays the category tree and lets
// administrators add, edit, rearrange, retire and delete categories, and bind leaf categories to
// the schema version their submissions must follow.
function CategoryManager() {
  const { tree, loading, loadError, create, update, move, reorder, setRetired, remove } = useCategoryTree();
  const [ dialog, setDialog ] = useState<DialogState>();
  const [ deleteTarget, setDeleteTarget ] = useState<CategoryNode>();
  const [ actionError, setActionError ] = useState<string>();

  const showError = (error: unknown) =>
    setActionError(error instanceof Error ? error.message : String(error));

  const actions: CategoryActions = {
    onEdit: node => setDialog({ mode: "edit", node, parentPath: parentPathOf(node.path) }),
    onAddChild: node => setDialog({ mode: "create", parentPath: node.path }),
    onDelete: node => setDeleteTarget(node),
    onToggleRetired: node => { setRetired(node.path, !node.retired).catch(showError); },
    onReorder: (path, order) => { reorder(path, order).catch(showError); },
  };

  // Create saves the new category (deriving its fields' types on the server), then, since the
  // creation POST already targets the right parent, nothing more; edit updates in place and then
  // moves the category if a different parent was picked.
  const save = async ({ fields, parentPath }: CategorySubmission): Promise<void> => {
    if (!dialog) {
      return;
    }
    if (dialog.mode === "create") {
      await create(dialog.parentPath, fields);
    } else if (dialog.node) {
      await update(dialog.node.path, fields);
      if (parentPath !== parentPathOf(dialog.node.path)) {
        await move(dialog.node.path, parentPath);
      }
    }
  };

  return (
    <AdminScreen
      title="Submission categories"
      action={
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setDialog({ mode: "create", parentPath: CATEGORIES_ROOT })}
        >
          New category
        </Button>
      }
    >
      <LoadingOverlay open={loading} />
      { !loading && tree.length === 0 && !loadError
        && (
          <Typography color="textSecondary">
            No categories are defined yet. Use &quot;New category&quot; to create the first one.
          </Typography>
        )}
      <CategoryTree nodes={tree} actions={actions} />
      { dialog
        && (
          <CategoryDialog
            mode={dialog.mode}
            node={dialog.node}
            parentPath={dialog.parentPath}
            tree={tree}
            onClose={() => setDialog(undefined)}
            onSave={save}
          />
        )}
      { deleteTarget
        && (
          <DeleteCategoryDialog
            node={deleteTarget}
            onClose={() => setDeleteTarget(undefined)}
            onDelete={node => remove(node.path)}
            onRetire={node => setRetired(node.path, true)}
          />
        )}
      <ErrorDialog
        open={!!loadError || !!actionError}
        title={loadError ? "The categories could not be loaded" : "The change could not be applied"}
        onClose={() => setActionError(undefined)}
      >
        { loadError ?? actionError }
      </ErrorDialog>
    </AdminScreen>
  );
}

export default CategoryManager;
