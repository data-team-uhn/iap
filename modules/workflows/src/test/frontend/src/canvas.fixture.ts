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

import { act } from "@testing-library/react";
import CommandStack from "diagram-js/lib/command/CommandStack";
import EventBus from "diagram-js/lib/core/EventBus";
import { Injector } from "didi";

import type { ModdleElement } from "@iap/workflows/moddle";
import panelEditsModule from "@iap/workflows/panelEdits";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

interface Update {
  element: Element;
  target: ModdleElement;
  properties: Record<string, unknown>;
  before?: Record<string, unknown>;
}

// A namespaced attribute no package declares is kept in `$attrs`, as moddle keeps one
const write = (target: ModdleElement, properties: Record<string, unknown>) =>
  Object.entries(properties).forEach(([ name, value ]) => {
    if (name.includes(":")) {
      target.$attrs = { ...target.$attrs, [name]: value as string | undefined };
    } else {
      target[name] = value;
    }
  });

const read = (target: ModdleElement, names: string[]) =>
  Object.fromEntries(names.map(name => [ name, name.includes(":") ? target.$attrs?.[name] : target[name] ]));

// An edit that writes in place and reports its element changed, as each of bpmn-js's modeling commands does
const UPDATE_HANDLER = {
  execute: (context: Update) => {
    context.before = read(context.target, Object.keys(context.properties));
    write(context.target, context.properties);
    return [ context.element ];
  },
  revert: (context: Update) => {
    write(context.target, context.before ?? {});
    return [ context.element ];
  },
};

// A canvas on diagram-js's own event bus and command stack, with the panel's command registered. Listener
// priorities, grouped commands and undo then behave as in the modeler. Modeling is stood in for by
// recorded calls, which `writeThrough` turns into commands. Any other service is given as is.
export function createCanvas(services: Record<string, unknown> = {}, definitions: ModdleElement = {}) {
  const updateLabel = vi.fn();
  const updateModdleProperties = vi.fn();
  const registered = new Map<string, Element>();
  const injector = new Injector([ {
    eventBus: [ "type", EventBus ],
    commandStack: [ "type", CommandStack ],
    modeling: [ "value", { updateLabel, updateModdleProperties } ],
    elementRegistry: [ "value", {
      get: (id: string) => registered.get(id),
      add: (element: Element) => registered.set(element.id, element),
    } ],
    ...Object.fromEntries(Object.entries(services).map(([ name, service ]) => [ name, [ "value", service ] ])),
  }, panelEditsModule ]);
  injector.init();
  const eventBus = injector.get<EventBus>("eventBus");
  const commandStack = injector.get<CommandStack>("commandStack");
  commandStack.register("test.update", UPDATE_HANDLER);

  const writeThrough = () => {
    updateLabel.mockImplementation((element: Element, label: string) =>
      commandStack.execute("test.update",
        { element, target: element.businessObject as ModdleElement, properties: { name: label } }));
    updateModdleProperties.mockImplementation(
      (element: Element, target: ModdleElement, properties: Record<string, unknown>) =>
        commandStack.execute("test.update", { element, target, properties }));
  };

  const viewer = {
    get: vi.fn((name: string) => injector.get<unknown>(name)),
    getDefinitions: vi.fn(() => definitions),
    on: eventBus.on.bind(eventBus),
    off: eventBus.off.bind(eventBus),
  } as unknown as BaseViewer;

  // `async` so that `act` hands back a promise, and the effects the event sets off are flushed before
  // the caller goes on
  const fire = (event: string, payload: object = {}) => act(async () => {
    eventBus.fire(event, payload);
  });

  return { viewer, eventBus, commandStack, registered, updateLabel, updateModdleProperties, writeThrough, fire };
}
