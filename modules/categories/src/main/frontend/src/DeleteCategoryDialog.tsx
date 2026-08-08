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

import { Alert, DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";

import { RETIREMENT_EFFECT } from "./RetireCategoryDialog";
import { CategoryReferencedError } from "./useCategoryTree";

import type { CategoryNode } from "./categoryModel";

interface DeleteCategoryDialogProps {
  node: CategoryNode;
  onClose: () => void;
  // Deletes the category; a rejection keeps the dialog open and displays the error.
  onDelete: (node: CategoryNode) => Promise<void>;
  // Retires the category instead, offered when deletion is refused because submissions exist.
  onRetire: (node: CategoryNode) => Promise<void>;
}

// The delete confirmation dialog. When the server refuses the deletion because the category
// has submissions, the dialog switches to offering to retire the category instead.
function DeleteCategoryDialog({ node, onClose, onDelete, onRetire }: DeleteCategoryDialogProps) {
  const [ referenced, setReferenced ] = useState(false);

  // A refused deletion is not a failure to report but a fork in the dialog: the deletion comes off
  // the table and the retirement it suggested takes its place.
  const takeRefusalAsAnOffer = (error: unknown): boolean => {
    if (error instanceof CategoryReferencedError) {
      setReferenced(true);
      return true;
    }
    return false;
  };

  return (
    <ConfirmActionDialog
      title={`Delete ${node.label}?`}
      confirmLabel={referenced ? "Retire instead" : "Delete"}
      confirmColor={referenced ? "warning" : "error"}
      onConfirm={referenced ? () => onRetire(node) : () => onDelete(node)}
      // Only a deletion can be refused this way; a failed retirement is reported, not reinterpreted
      interceptFailure={referenced ? undefined : takeRefusalAsAnOffer}
      onClose={onClose}
    >
      { referenced
        ? (
          <Alert severity="warning">
            This category has submissions and cannot be deleted, but it can be retired. {RETIREMENT_EFFECT}
          </Alert>
        )
        : (
          <DialogContentText>
            The category &quot;{node.label}&quot; will be permanently deleted. This cannot be undone.
          </DialogContentText>
        )}
    </ConfirmActionDialog>
  );
}

export default DeleteCategoryDialog;
