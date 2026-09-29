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

import { type ReactNode, useState } from "react";

import ConditionDialog from "@iap/conditions/ConditionDialog";

import { questionsFor } from "./conditionModel";
import QuestionPicker from "./QuestionPicker";
import { type JcrNode, offers } from "./schemaModel";
import { useMoveMode } from "./schemaMove";
import { useTreeEvent } from "./schemaTree";
import { conditionOf } from "./schemaVersionTreeModel";
import { useVersionConditions } from "./versionConditions";

export interface ConditionEditor {
  // Opens it, where the condition can be set and nothing is moving
  edit?: () => void;
  // The dialog, while it is open
  dialog: ReactNode;
}

// Sets when a part applies: the condition it is asked under, built from the answers to the version's other
// questions, how a submission is tagged, and its properties, and written whole in one event. A card opens it from
// its actions, and from the line saying when it applies.
export function useConditionEditor(node: JcrNode, what: string): ConditionEditor {
  const [ editing, setEditing ] = useState(false);
  const { index, offered } = useVersionConditions();
  const { moving } = useMoveMode();
  const send = useTreeEvent();
  if (!offers(node, "condition")) {
    return { dialog: null };
  }
  const questions = questionsFor(node, index);
  return {
    edit: moving ? undefined : () => setEditing(true),
    dialog: editing && (
      <ConditionDialog
        title={`When this ${what} applies`}
        condition={conditionOf(node)}
        sources={offered}
        editors={{ answer: editor => <QuestionPicker {...editor} questions={questions} /> }}
        onClose={() => setEditing(false)}
        onSave={content => send(node, "condition", { content: JSON.stringify(content) })}
      />
    ),
  };
}
