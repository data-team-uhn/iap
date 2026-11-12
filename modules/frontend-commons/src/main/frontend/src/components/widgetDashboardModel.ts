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
// and how many columns a run of widgets takes. Nothing here renders or fetches.
//
// A widget group is a data-only extension on the dashboard's own point, marked with
// `ext:isWidgetGroup: true`. A widget names its group by the group's node name, in
// `ext:widgetGroup`. The group's `ext:name` is its title. A widget naming a group that does not
// exist, because it was renamed, disabled or never installed, is listed under Other.

// One extension registered on a dashboard's point, either a widget or a widget group.
export type DashboardExtension = Record<string, unknown>;

// The title over the widgets that belong to no existing group.
export const OTHER_LABEL = "Other";

// One run of widgets on the dashboard, under one title. `name` is the group's node name, or null for
// the widgets of no group. `label` is the title to show. It is null only for the widgets of no group
// on a dashboard that shows no group at all.
export interface WidgetGroupSlot {
  name: string | null;
  label: string | null;
  widgets: DashboardExtension[];
}

// Whether an extension of a dashboard's point is a widget group rather than a widget.
export const isWidgetGroup = (extension: DashboardExtension): boolean => extension["ext:isWidgetGroup"] === true;

// Whether an extension of a dashboard's point is a widget that can be shown: it has a component in
// `ext:render`. Only an `asset:` property loads one, so a widget declaring none has nothing to show.
export const isRenderableWidget = (extension: DashboardExtension): boolean =>
  !isWidgetGroup(extension) && Boolean(extension["ext:render"]);

const nonEmptyString = (value: unknown): string | undefined =>
  typeof value === "string" && value !== "" ? value : undefined;

// Files the visible widgets under their groups. The groups keep the order they are given in, which
// is their `defaultOrder`, and the widgets keep theirs within a group. A group with no visible widget
// is left out. The widgets of no existing group come last, under Other, which has no title when no
// group is shown. A hidden group hides its widgets too, rather than sending them to Other.
export function groupWidgets(
  widgets: DashboardExtension[],
  groups: DashboardExtension[],
  visible: (extension: DashboardExtension) => boolean
): WidgetGroupSlot[] {
  // null for a group that exists but is hidden; a group that does not exist has no entry
  const slots = new Map<string, WidgetGroupSlot | null>();
  for (const group of groups) {
    const name = nonEmptyString(group["@name"]);
    // Of two groups with the same name, the first wins
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

// How many columns a run of `count` widgets takes at the `sm` and `lg` breakpoints; below `sm` it is
// one. A run of one or two widgets takes as many columns as it has widgets, so no column stays
// empty. Three or more take two columns at `sm` and three at `lg`.
export function gridColumns(count: number): { sm: number; lg: number } {
  return { sm: Math.min(count, 2) || 1, lg: Math.min(count, 3) || 1 };
}
