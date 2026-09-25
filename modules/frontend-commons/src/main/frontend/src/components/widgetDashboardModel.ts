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

// How a widget dashboard is organized: which widgets go under which widget group, in what order,
// and how many columns a run of widgets is laid out in. No React, no fetch: everything here is a
// pure function of its arguments, so the rules can be tested without rendering a dashboard. The
// rendering is in WidgetDashboard.
//
// A widget group is a data-only extension registered on the dashboard's own point, told apart from
// the widgets by `ext:isWidgetGroup: true`. Its node name is what widgets refer to, through their
// `ext:widgetGroup`, and its `ext:name` is the title displayed. The reference is deliberately soft:
// a widget naming a group that does not exist (renamed, disabled, not installed) is listed under
// Other rather than lost.

// One extension registered on a dashboard's point, widget or widget group.
export type DashboardExtension = Record<string, unknown>;

// The title of the trailing run of widgets that belong to no (existing) group.
export const OTHER_LABEL = "Other";

// One titled run of widgets on the dashboard. `name` is the group's node name, or null for the
// ungrouped widgets; `label` is the title to display, or null when none should be — which is only
// the case for the ungrouped widgets of a dashboard that has no group to show at all.
export interface WidgetGroupSlot {
  name: string | null;
  label: string | null;
  widgets: DashboardExtension[];
}

// Whether an extension of a dashboard's point is a widget group rather than a widget. The marker is
// explicit rather than inferred from, say, a missing `ext:renderURL`, since a widget that is only a
// label and a link would have no component either.
export const isWidgetGroup = (extension: DashboardExtension): boolean => extension["ext:isWidgetGroup"] === true;

const nonEmptyString = (value: unknown): string | undefined =>
  typeof value === "string" && value !== "" ? value : undefined;

// Files the visible widgets under their groups. The groups keep the order they are given in (their
// `defaultOrder`, since that is how extensions are served) and the widgets keep theirs within each
// group; groups holding no visible widget are omitted, and widgets belonging to no existing group
// come last, under Other — untitled when there is no group to show, so a dashboard that never
// defined any looks exactly as it would without groups. A widget is shown only if both it and its
// group are visible: a hidden group hides its widgets, rather than sending them to Other.
export function groupWidgets(
  widgets: DashboardExtension[],
  groups: DashboardExtension[],
  visible: (extension: DashboardExtension) => boolean
): WidgetGroupSlot[] {
  // Null for a group that exists but is hidden, as opposed to one that does not exist at all
  const slots = new Map<string, WidgetGroupSlot | null>();
  for (const group of groups) {
    const name = nonEmptyString(group["@name"]);
    // The first of two groups sharing a name wins, like the first of two routes matching a path
    if (name !== undefined && !slots.has(name)) {
      slots.set(name, visible(group) ? { name, label: nonEmptyString(group["ext:name"]) ?? name, widgets: [] } : null);
    }
  }

  const other: DashboardExtension[] = [];
  for (const widget of widgets.filter(visible)) {
    const groupName = nonEmptyString(widget["ext:widgetGroup"]);
    if (groupName === undefined || !slots.has(groupName)) {
      other.push(widget);
    } else {
      slots.get(groupName)?.widgets.push(widget);
    }
  }

  const result = [ ...slots.values() ].filter(
    (slot): slot is WidgetGroupSlot => slot !== null && slot.widgets.length > 0
  );
  if (other.length > 0) {
    result.push({ name: null, label: result.length > 0 ? OTHER_LABEL : null, widgets: other });
  }
  return result;
}

// How many columns a run of `count` widgets is laid out in, at the `sm` and `lg` breakpoints (below
// `sm` it is always one). The grid collapses to the number of widgets when there are only one or
// two, so a lone widget fills the row and two sit side by side rather than leaving empty columns;
// three or more get the full responsive spread.
export function gridColumns(count: number): { sm: number; lg: number } {
  return { sm: Math.min(count, 2) || 1, lg: Math.min(count, 3) || 1 };
}
