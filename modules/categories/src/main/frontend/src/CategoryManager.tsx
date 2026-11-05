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
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { messageOf } from "@iap/frontend-commons/requestFailure";

import CategoryDialog, { SaveStepFailure, type CategorySubmission, type SaveStep } from "./CategoryDialog";
import CategoryLoadError from "./CategoryLoadError";
import CategoryTree, { type CategoryActions } from "./CategoryTree";
import DeleteCategoryDialog from "./DeleteCategoryDialog";
import RetireCategoryDialog from "./RetireCategoryDialog";
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

// Runs one write of a save, and marks a failure with the step it belongs to.
const attempt = async (step: SaveStep, write: () => Promise<unknown>): Promise<void> => {
  try {
    await write();
  } catch (error: unknown) {
    throw new SaveStepFailure(step, error);
  }
};

// The "Submission categories" administration screen. Administrators add, edit, rearrange, retire
// and delete categories there, and bind each leaf to the schema version its submissions follow.
function CategoryManager() {
  const {
    tree, loading, loadError, reload,
    create, update, unbindSchemaVersion, move, reorder, setRetired, remove,
  } = useCategoryTree();
  const [ dialog, setDialog ] = useState<DialogState>();
  const [ deleteTarget, setDeleteTarget ] = useState<CategoryNode>();
  const [ retireTarget, setRetireTarget ] = useState<CategoryNode>();
  const notify = useNotice();

  // Runs a row action that acts as soon as it is clicked. It has no dialog to report in, so a
  // failure raises a notice over the tree, titled with what did not happen and offering a retry. A
  // retry that fails raises a notice of its own.
  const run = (title: string, action: () => Promise<void>): void => {
    action().catch((error: unknown) => notify({
      title,
      message: messageOf(error),
      onRetry: () => { run(title, action); },
    }));
  };

  const actions: CategoryActions = {
    onEdit: node => setDialog({ mode: "edit", node, parentPath: parentPathOf(node.path) }),
    onAddChild: node => setDialog({ mode: "create", parentPath: node.path }),
    onDelete: node => setDeleteTarget(node),
    // Retiring asks for confirmation, and unretiring acts at once. The tree disables the action on a
    // category retired only through an ancestor.
    onToggleRetired: node => {
      if (node.retiredHere) {
        run(`${node.label} could not be unretired`, () => setRetired(node.path, false));
      } else {
        setRetireTarget(node);
      }
    },
    // The title names the direction, since the edit dialog also moves a category, to another parent
    onReorder: (node, order, direction) => {
      run(`${node.label} could not be moved ${direction}`, () => reorder(node.path, order));
    },
  };

  // Creating writes the category under its parent in one request. Editing updates the fields in
  // place, then moves the category if another parent was picked.
  //
  // A parent about to gain its first child gives up its schema version before anything else is
  // written. If a later step fails, the parent is still a leaf, and the picker still offers to bind
  // it again. A parent that gained a child and kept its binding would not show the picker at all.
  const save = async ({ fields, parentPath, unbindParent }: CategorySubmission): Promise<void> => {
    if (!dialog) {
      return;
    }
    if (unbindParent) {
      await attempt("unbind", () => unbindSchemaVersion(parentPath));
    }
    if (dialog.mode === "create") {
      await attempt("create", () => create(dialog.parentPath, fields));
    } else if (dialog.node) {
      const { path } = dialog.node;
      await attempt("update", () => update(path, fields));
      if (parentPath !== parentPathOf(path)) {
        await attempt("move", () => move(path, parentPath));
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
      disablePanel
    >
      <LoadingOverlay open={loading} />
      { /* Shown above the tree, not instead of it: the reload after a write can fail too, and the
           earlier tree stays readable. */ }
      { loadError && <CategoryLoadError message={loadError} onRetry={reload} sx={{ mb: 2 }} /> }
      { !loading && tree.length === 0 && !loadError
        && (
          <Typography variant="placeholder">
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
      { retireTarget
        && (
          <RetireCategoryDialog
            node={retireTarget}
            onClose={() => setRetireTarget(undefined)}
            onRetire={node => setRetired(node.path, true)}
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
    </AdminScreen>
  );
}

export default CategoryManager;
