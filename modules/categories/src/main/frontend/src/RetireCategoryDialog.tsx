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

import { DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";

import type { CategoryNode } from "./categoryModel";

// What retiring does, in the words both paths to it use: the row action below, and the offer the
// deletion dialog makes when a category has submissions. Retirement is the one category action
// whose effect lands on submitters rather than on the administrator doing it, so it is spelled out
// wherever it is offered.
export const RETIREMENT_EFFECT = "Existing submissions stay in this category and keep working as "
  + "before; only new submissions are prevented. Retiring can be undone at any time.";

interface RetireCategoryDialogProps {
  node: CategoryNode;
  onClose: () => void;
  // Retires the category; a rejection keeps the dialog open and displays the error.
  onRetire: (node: CategoryNode) => Promise<void>;
}

// The retirement confirmation. Retiring is reversible, so this asks less to guard the change than
// to explain it: from the tree it is one small button among several, and its consequence is
// invisible from here.
function RetireCategoryDialog({ node, onClose, onRetire }: RetireCategoryDialogProps) {
  // Retirement is inherited, so retiring a branch closes everything under it as well. That is the
  // part an administrator cannot see from the row they clicked, so it is the part worth naming.
  const subject = node.children.length > 0
    ? `"${node.label}" and its subcategories`
    : `"${node.label}"`;

  return (
    <ConfirmActionDialog
      title={`Retire ${node.label}?`}
      confirmLabel="Retire"
      confirmColor="warning"
      onConfirm={() => onRetire(node)}
      onClose={onClose}
    >
      <DialogContentText>
        {subject} will no longer be available for new submissions. {RETIREMENT_EFFECT}
      </DialogContentText>
    </ConfirmActionDialog>
  );
}

export default RetireCategoryDialog;
