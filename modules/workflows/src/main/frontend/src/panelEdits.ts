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

// The command that writes several of the properties panel's edits as one step to undo.

import type { ModdleElement } from "./moddle";
import type BpmnModeling from "bpmn-js/lib/features/modeling/Modeling";
import type BpmnReplace from "bpmn-js/lib/features/replace/BpmnReplace";
import type { TargetElement } from "bpmn-js/lib/features/replace/BpmnReplace";
import type { Element } from "bpmn-js/lib/model/Types";
import type CommandStack from "diagram-js/lib/command/CommandStack";

export const PANEL_EDITS_COMMAND = "iap.properties.update";

// What bpmn-js is asked to redraw an element as: the type of element, the event it carries if it is an event,
// and whether that event interrupts the activity it belongs to.
export interface ReplaceTarget {
  type: string;
  eventDefinitionType?: string;
  cancelActivity?: boolean;
  isInterrupting?: boolean;
}

// One edit the panel makes: a new name, new properties on one moddle object of the element, or the element
// redrawn as another kind. A redrawn element is a new object under the same id, so what is written to it after is
// given as a function of it.
export type PanelEdit =
  | { element: Element; label: string }
  | { element: Element; moddleElement: ModdleElement; properties: Record<string, unknown> }
  | { element: Element; replaceWith: ReplaceTarget; then: (replaced: Element) => PanelEdit[] };

export interface PanelEditsContext {
  edits: PanelEdit[];
}

// Each edit is a modeling command of its own. Run before the command itself, they share its place on
// the stack, which is how bpmn-js groups the commands of one operation.
class PanelEditsHandler {
  static $inject = [ "modeling", "bpmnReplace" ];

  private readonly modeling: BpmnModeling;

  private readonly replace: BpmnReplace;

  constructor(modeling: BpmnModeling, replace: BpmnReplace) {
    this.modeling = modeling;
    this.replace = replace;
  }

  preExecute(context: PanelEditsContext) {
    context.edits.forEach(edit => this.run(edit));
  }

  private run(edit: PanelEdit) {
    if ("label" in edit) {
      this.modeling.updateLabel(edit.element, edit.label);
    } else if ("replaceWith" in edit) {
      const replaced = this.replace.replaceElement(edit.element, edit.replaceWith as TargetElement);
      edit.then(replaced).forEach(next => this.run(next));
    } else {
      this.modeling.updateModdleProperties(edit.element, edit.moddleElement, edit.properties);
    }
  }
}

function registerPanelEdits(commandStack: CommandStack) {
  commandStack.registerHandler(PANEL_EDITS_COMMAND, PanelEditsHandler);
}
registerPanelEdits.$inject = [ "commandStack" ];

// What a modeler is given to accept the command.
const panelEditsModule = { __init__: [ registerPanelEdits ] };

export default panelEditsModule;
