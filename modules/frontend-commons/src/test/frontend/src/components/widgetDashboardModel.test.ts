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

import {
  gridColumns,
  groupWidgets,
  isWidgetGroup,
  OTHER_LABEL,
} from "@iap/frontend-commons/components/widgetDashboardModel";

// A widget as the dashboard receives it, optionally filed under a group.
const widget = (name: string, group?: string) => ({
  "ext:name": name,
  ...(group === undefined ? {} : { "ext:widgetGroup": group }),
});

// A group node as the dashboard receives it: identified by its node name, labelled by ext:name.
const group = (name: string, label: string) => ({
  "@name": name,
  "ext:name": label,
  "ext:isWidgetGroup": true,
});

// A visibility predicate letting everything through, for the tests not about visibility.
const everyone = () => true;

// The shape of the result that matters in most tests: which titles, holding which widgets.
const outline = (slots: ReturnType<typeof groupWidgets>) =>
  slots.map(slot => [ slot.label, slot.widgets.map(w => w["ext:name"]) ]);

describe("isWidgetGroup", () => {
  it("recognizes a group node by its explicit marker", () => {
    expect(isWidgetGroup(group("Configuration", "Configuration"))).toBe(true);
  });

  it("does not take a data-only widget for a group", () => {
    // A widget without a component (a plain link, say) is still a widget: absence of a render
    // URL is not what makes a group
    expect(isWidgetGroup({ "ext:name": "Link only", "ext:targetURL": "/admin/somewhere" })).toBe(false);
  });

  it("only accepts a true marker", () => {
    expect(isWidgetGroup({ ...group("G", "G"), "ext:isWidgetGroup": false })).toBe(false);
    expect(isWidgetGroup({ ...group("G", "G"), "ext:isWidgetGroup": "true" })).toBe(false);
  });
});

describe("groupWidgets", () => {
  it("files widgets under their groups, in the groups' order", () => {
    const slots = groupWidgets(
      [ widget("Errors", "Operations"), widget("Categories", "Configuration"), widget("Workflows", "Configuration") ],
      [ group("Configuration", "Configuration"), group("Operations", "Operations") ],
      everyone
    );

    expect(outline(slots)).toEqual([
      [ "Configuration", [ "Categories", "Workflows" ] ],
      [ "Operations", [ "Errors" ] ],
    ]);
    expect(slots.map(slot => slot.name)).toEqual([ "Configuration", "Operations" ]);
  });

  it("titles a group with its label, which need not be its node name", () => {
    const slots = groupWidgets([ widget("Errors", "ops") ], [ group("ops", "Operations") ], everyone);

    expect(slots).toEqual([ { name: "ops", label: "Operations", widgets: [ widget("Errors", "ops") ] } ]);
  });

  it("falls back to the node name when a group has no label", () => {
    const unlabelled: Record<string, unknown> = group("Operations", "");
    delete unlabelled["ext:name"];

    expect(outline(groupWidgets([ widget("Errors", "Operations") ], [ unlabelled ], everyone))).toEqual([
      [ "Operations", [ "Errors" ] ],
    ]);
  });

  it("omits a group none of the widgets belong to", () => {
    const slots = groupWidgets(
      [ widget("Errors", "Operations") ],
      [ group("Configuration", "Configuration"), group("Operations", "Operations") ],
      everyone
    );

    expect(outline(slots)).toEqual([ [ "Operations", [ "Errors" ] ] ]);
  });

  it("lists ungrouped widgets last, under Other", () => {
    const slots = groupWidgets(
      [ widget("Loose"), widget("Errors", "Operations") ],
      [ group("Operations", "Operations") ],
      everyone
    );

    expect(outline(slots)).toEqual([
      [ "Operations", [ "Errors" ] ],
      [ OTHER_LABEL, [ "Loose" ] ],
    ]);
    expect(slots[1].name).toBeNull();
  });

  it("sends a widget whose group does not exist to Other rather than dropping it", () => {
    const slots = groupWidgets(
      [ widget("Orphan", "Renamed"), widget("Errors", "Operations") ],
      [ group("Operations", "Operations") ],
      everyone
    );

    expect(outline(slots)).toEqual([
      [ "Operations", [ "Errors" ] ],
      [ OTHER_LABEL, [ "Orphan" ] ],
    ]);
  });

  it("does not title Other when every widget is ungrouped", () => {
    const slots = groupWidgets([ widget("One"), widget("Two", "Missing") ], [ group("Unused", "Unused") ], everyone);

    expect(slots).toEqual([ { name: null, label: null, widgets: [ widget("One"), widget("Two", "Missing") ] } ]);
  });

  it("still titles a group when it holds every widget", () => {
    expect(outline(groupWidgets([ widget("Errors", "Operations") ], [ group("Operations", "Operations") ], everyone)))
      .toEqual([ [ "Operations", [ "Errors" ] ] ]);
  });

  it("keeps the first of two groups sharing a node name", () => {
    const slots = groupWidgets(
      [ widget("Errors", "Operations") ],
      [ group("Operations", "Operations"), group("Operations", "Duplicate") ],
      everyone
    );

    expect(outline(slots)).toEqual([ [ "Operations", [ "Errors" ] ] ]);
  });

  it("ignores a group node without a name", () => {
    const nameless: Record<string, unknown> = group("", "Nameless");
    delete nameless["@name"];

    expect(outline(groupWidgets([ widget("Loose") ], [ nameless ], everyone))).toEqual([ [ null, [ "Loose" ] ] ]);
  });

  it("leaves out the widgets that are not visible", () => {
    const hidden = { ...widget("Reviews", "Operations"), hidden: true };
    const slots = groupWidgets(
      [ widget("Errors", "Operations"), hidden, { ...widget("Loose"), hidden: true } ],
      [ group("Operations", "Operations") ],
      extension => extension.hidden !== true
    );

    expect(outline(slots)).toEqual([ [ "Operations", [ "Errors" ] ] ]);
  });

  it("hides a group that is not visible along with its widgets, rather than sending them to Other", () => {
    const slots = groupWidgets(
      [ widget("Categories", "Configuration"), widget("Errors", "Operations"), widget("Loose") ],
      [ group("Configuration", "Configuration"), { ...group("Operations", "Operations"), hidden: true } ],
      extension => extension.hidden !== true
    );

    expect(outline(slots)).toEqual([
      [ "Configuration", [ "Categories" ] ],
      [ OTHER_LABEL, [ "Loose" ] ],
    ]);
  });

  it("shows a widget only if both it and its group are visible", () => {
    const slots = groupWidgets(
      [ { ...widget("Hidden in a visible group", "Configuration"), hidden: true } ],
      [ group("Configuration", "Configuration") ],
      extension => extension.hidden !== true
    );

    expect(slots).toEqual([]);
  });

  it("returns nothing for no widgets", () => {
    expect(groupWidgets([], [ group("Operations", "Operations") ], everyone)).toEqual([]);
  });
});

describe("gridColumns", () => {
  it("collapses the grid to the number of widgets when there are fewer than the columns", () => {
    expect(gridColumns(1)).toEqual({ sm: 1, lg: 1 });
    expect(gridColumns(2)).toEqual({ sm: 2, lg: 2 });
  });

  it("uses the full responsive spread from three widgets on", () => {
    expect(gridColumns(3)).toEqual({ sm: 2, lg: 3 });
    expect(gridColumns(4)).toEqual({ sm: 2, lg: 3 });
  });

  it("never asks for zero columns", () => {
    expect(gridColumns(0)).toEqual({ sm: 1, lg: 1 });
  });
});
