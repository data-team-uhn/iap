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
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
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

// Runs one write of a save, marking any failure with the step it belongs to so the dialog can say
// which of them did not happen.
const attempt = async (step: SaveStep, write: () => Promise<unknown>): Promise<void> => {
  try {
    await write();
  } catch (error: unknown) {
    throw new SaveStepFailure(step, error);
  }
};

// The "Submission categories" administrative tool: displays the category tree and lets
// administrators add, edit, rearrange, retire and delete categories, and bind leaf categories to
// the schema version their submissions must follow.
function CategoryManager() {
  const { tree, loading, loadError, reload, create, update, move, reorder, setRetired, remove } = useCategoryTree();
  const [ dialog, setDialog ] = useState<DialogState>();
  const [ deleteTarget, setDeleteTarget ] = useState<CategoryNode>();
  const [ retireTarget, setRetireTarget ] = useState<CategoryNode>();
  const [ notice, setNotice ] = useState<Notice>();

  // The row actions that act the moment they are clicked have no dialog of their own to report
  // back in, so they report over the tree instead - naming what did not happen, and offering the
  // attempt again. A retry that fails in turn raises its own notice.
  const run = (title: string, action: () => Promise<void>): void => {
    action().catch((error: unknown) => setNotice({
      title,
      message: messageOf(error),
      onRetry: () => { run(title, action); },
    }));
  };

  const actions: CategoryActions = {
    onEdit: node => setDialog({ mode: "edit", node, parentPath: parentPathOf(node.path) }),
    onAddChild: node => setDialog({ mode: "create", parentPath: node.path }),
    onDelete: node => setDeleteTarget(node),
    // Retiring is confirmed, because its effect lands on submitters rather than here; unretiring is
    // that confirmation's undo, so asking again for it would be noise.
    onToggleRetired: node => {
      if (node.retired) {
        run(`${node.label} could not be unretired`, () => setRetired(node.path, false));
      } else {
        setRetireTarget(node);
      }
    },
    onReorder: (node, order) => {
      run(`${node.label} could not be moved`, () => reorder(node.path, order));
    },
  };

  // Create saves the new category (deriving its fields' types on the server), then, since the
  // creation POST already targets the right parent, nothing more; edit updates in place and then
  // moves the category if a different parent was picked.
  const save = async ({ fields, parentPath }: CategorySubmission): Promise<void> => {
    if (!dialog) {
      return;
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
    >
      <LoadingOverlay open={loading} />
      { /* Reported above the tree rather than in place of it: reloading follows every write too,
           so this doubles as the "your view is out of date" report over a still-readable tree. */ }
      { loadError && <CategoryLoadError message={loadError} onRetry={reload} sx={{ mb: 2 }} /> }
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
      <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
    </AdminScreen>
  );
}

export default CategoryManager;
