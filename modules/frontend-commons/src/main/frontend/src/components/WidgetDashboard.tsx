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

import { useCallback, useEffect, useId, useMemo, useState, type ComponentType, type ReactNode } from "react";

import ArrowForwardIcon from "@mui/icons-material/ArrowForward";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { Box, Button, ButtonBase, Collapse, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { loadExtensions, visibleInPersona } from "@iap/ui-extension/extensionManager";
import { usePersona } from "@iap/ui-extension/personas";

import LoadingOverlay from "./LoadingOverlay";
import Widget from "./Widget";
import {
  gridColumns,
  groupWidgets,
  isWidgetGroup,
  type DashboardExtension,
  type WidgetGroupSlot,
} from "./widgetDashboardModel";

// A widget extension is the parsed JSON of one `ext:Extension` registered on the dashboard's
// extension point, with its `asset:` properties already resolved.
type WidgetExtension = DashboardExtension;

// The props that the dashboard passes to each rendered widget.
interface WidgetContentProps {
  extension: WidgetExtension;
}

// How many columns each `ext:widgetWidth` value asks for. The actual span is clamped (in JS) to the
// number of columns available at each breakpoint, so `full` fills the row and a span never exceeds
// the grid — a span larger than the column count would otherwise make CSS Grid spawn extra columns.
const WIDTH_SPAN: Record<string, number> = { normal: 1, wide: 2, full: 3 };

// A React key that identifies a widget rather than its position, since the persona filter changes
// which widgets are displayed while the page is up. Keying by index would hand one widget's key to
// another as the list shrinks, remounting a widget that only moved and throwing away its state.
function widgetKey(widget: WidgetExtension, index: number): string {
  return (widget["@path"] as string | undefined)
    ?? (widget["ext:name"] as string | undefined)
    ?? `widget-${index}`;
}

// Which groups of a dashboard the viewer has collapsed, remembered in this browser so that the
// dashboard looks the same when they come back to it from a tool. A group is remembered by its node
// name; the run of ungrouped widgets, titled Other or untitled, by the empty string, which no node can be
// named. Storage is a convenience: when it is unavailable, every group simply starts expanded.
const collapsedStorageKey = (point: string) => `iap.widgetDashboard.${point}.collapsed`;
const slotId = (slot: WidgetGroupSlot) => slot.name ?? "";

const readCollapsed = (point: string): string[] => {
  try {
    const stored: unknown = JSON.parse(window.localStorage.getItem(collapsedStorageKey(point)) ?? "[]");
    return Array.isArray(stored) ? stored.filter((id): id is string => typeof id === "string") : [];
  } catch {
    // No storage (e.g. blocked), or something unreadable in it — start with everything expanded
    return [];
  }
};

const writeCollapsed = (point: string, ids: string[]) => {
  try {
    window.localStorage.setItem(collapsedStorageKey(point), JSON.stringify(ids));
  } catch {
    // No storage — the choice lasts until the page is left
  }
};

// The collapsed groups of the given dashboard, and a toggle for one of them. Remembered per point,
// so should the dashboard be pointed elsewhere, it picks up that point's choices, not these.
function useCollapsedGroups(point: string): [ string[], (id: string) => void ] {
  const [ state, setState ] = useState(() => ({ point, ids: readCollapsed(point) }));
  const ids = state.point === point ? state.ids : readCollapsed(point);
  const toggle = useCallback((id: string) => {
    const next = ids.includes(id) ? ids.filter(other => other !== id) : [ ...ids, id ];
    writeCollapsed(point, next);
    setState({ point, ids: next });
  }, [ point, ids ]);
  return [ ids, toggle ];
}

// One run of widgets in the dashboard's responsive CSS grid (1/2/3 columns), each wrapped in its
// titled Widget frame. The column count follows the run's own size, so within a group the layout is
// exactly what the whole dashboard would be with only that group's widgets.
function WidgetGrid({ widgets }: { widgets: WidgetExtension[] }) {
  // Spans are clamped to the column count, so an explicit `full` widget still takes the whole row
  // (and, e.g., forces a second widget onto the next row).
  const { sm: smColumns, lg: lgColumns } = gridColumns(widgets.length);

  return (
    <Box
      sx={{
        display: "grid",
        gap: 2,
        // Cells stretch (the grid default), so widgets sharing a row are the same height; each
        // Widget surface fills its cell (see Widget.tsx).
        gridTemplateColumns: {
          xs: "1fr",
          sm: `repeat(${smColumns}, 1fr)`,
          lg: `repeat(${lgColumns}, 1fr)`,
        },
      }}
    >
      {
        widgets.map((widget, index) => {
          const WidgetContent = widget["ext:render"] as ComponentType<WidgetContentProps>;
          const span = WIDTH_SPAN[(widget["ext:widgetWidth"] as string | undefined) ?? "normal"] ?? 1;
          const title = (widget["ext:name"] as string | undefined) ?? "";
          const actionLabel = widget["ext:actionLabel"] as string | undefined;
          const targetURL = widget["ext:targetURL"] as string | undefined;
          const action = actionLabel && targetURL
            ? (
              <Button
                size="small"
                variant="text"
                endIcon={<ArrowForwardIcon />}
                component={RouterLink}
                to={targetURL}
                // Action labels are short verbs, so several widgets legitimately share one; the
                // title is folded into the accessible name to tell them apart where the button is
                // read away from its header, as in a screen reader's list of links.
                aria-label={title ? `${actionLabel}: ${title}` : actionLabel}
              >
                {actionLabel}
              </Button>
            )
            : undefined;
          return (
            <Box
              key={widgetKey(widget, index)}
              sx={{
                gridColumn: {
                  xs: "span 1",
                  sm: `span ${Math.min(span, smColumns)}`,
                  lg: `span ${Math.min(span, lgColumns)}`,
                },
              }}
            >
              <Widget
                title={title}
                subtitle={widget["ext:subtitle"] ? (widget["ext:subtitle"] as string) : undefined}
                action={action}
                emphasis={Boolean(widget["ext:widgetEmphasis"])}
                borderless={Boolean(widget["ext:widgetBorderless"])}
                hideHeader={Boolean(widget["ext:widgetHideHeader"])}
              >
                <WidgetContent extension={widget} />
              </Widget>
            </Box>
          );
        })
      }
    </Box>
  );
}

interface WidgetGroupPanelProps {
  slot: WidgetGroupSlot;
  collapsed: boolean;
  onToggle: () => void;
}

// One widget group: a muted title across the whole row — no border, no surface — that collapses and
// expands the group's widgets, and says how many there are while they are hidden. A run with no
// title (the ungrouped widgets of a dashboard without groups) is only its grid. Either way the grid
// sits at the same place in the tree, so a group gaining or losing its title as the persona changes
// does not remount its widgets.
function WidgetGroupPanel({ slot, collapsed, onToggle }: WidgetGroupPanelProps) {
  const contentId = useId();
  const open = slot.label === null || !collapsed;

  return (
    <Box>
      { slot.label !== null && (
        <Typography variant="subheading" sx={{ mb: open ? 1 : 0 }}>
          <ButtonBase
            onClick={onToggle}
            aria-expanded={open}
            aria-controls={contentId}
            sx={theme => ({
              width: "100%",
              justifyContent: "flex-start",
              gap: 0.5,
              py: 0.5,
              borderRadius: 1,
              textAlign: "start",
              // Browsers reset these on a <button>; `font` does not cover textTransform or letterSpacing
              font: "inherit",
              textTransform: "inherit",
              letterSpacing: "inherit",
              color: "inherit",
              transition: theme.transitions.create("color", { duration: theme.transitions.duration.shortest }),
              "&:hover, &.Mui-focusVisible": { color: "primary.main" },
            })}
          >
            <ExpandMoreIcon
              sx={theme => ({
                fontSize: "1.5em",
                transform: open ? "none" : "rotate(-90deg)",
                "[dir=rtl] &": { transform: open ? "none" : "rotate(90deg)" },
                transition: theme.transitions.create("transform", { duration: theme.transitions.duration.shortest }),
              })}
            />
            { open ? slot.label : `${slot.label} (${slot.widgets.length})` }
          </ButtonBase>
        </Typography>
      ) }
      <Box id={contentId}>
        {/* Unmounted while collapsed: a collapsed group is one the viewer has said they do not need,
            so its widgets need not keep fetching what they summarize. */}
        <Collapse in={open} timeout="auto" unmountOnExit>
          <WidgetGrid widgets={slot.widgets} />
        </Collapse>
      </Box>
    </Box>
  );
}

interface WidgetDashboardProps {
  // The extension point node name whose extensions are laid out as widgets,
  // e.g. "DashboardWidget" for the user dashboard or "AdminDashboard" for the admin console.
  point: string;
  // Optional content rendered in place of the grid when no widgets are available (e.g. because
  // the user cannot read any). When omitted, an empty dashboard just renders nothing.
  empty?: ReactNode;
}

// The shared widget-dashboard layout: widgets contributed through the given extension point, laid
// out in a responsive CSS grid (1/2/3 columns). Every widget is wrapped in a titled Widget frame —
// the title from `ext:name`, an optional subtitle from `ext:subtitle` — and each widget
// can tune its frame through optional properties:
//   - `ext:widgetWidth` (normal/wide/full) — how many columns it spans (e.g. a `full` table
//     stretches across the row);
//   - `ext:widgetEmphasis` — render on a tinted surface;
//   - `ext:widgetBorderless` — drop the border/fill and blend into the page;
//   - `ext:widgetHideHeader` — skip the title/subtitle header (the widget provides its own);
//   - `ext:actionLabel` — render a header action in line with the title: a quiet text button
//     with this label and a forward arrow (navigation, not an inline operation), leading to the
//     widget's `ext:targetURL` (an in-app path); both must be set. Prefer a single verb naming
//     what the tool behind the summary is for ("Manage", "Triage") — the title beside it already
//     says which area it leads into, and it is repeated into the action's accessible name so that
//     the same verb on several widgets still reads unambiguously out of context.
//   - `ext:personas` — the personas the widget belongs to (absent means all of them), see personas.ts;
//   - `ext:widgetGroup` — the node name of the widget group it is listed under.
//
// Widget groups are registered on the same point as the widgets, as data-only extensions marked
// `ext:isWidgetGroup: true`, titled by `ext:name`, ordered by `defaultOrder`, and limited to personas
// by `ext:personas` like a widget, which hides the group's widgets along with it. Each group is a
// collapsible run of widgets laid out by the rules above; widgets in no (existing) group come last,
// under Other, which is left untitled when there is no group to show. See widgetDashboardModel.ts.
function WidgetDashboard({ point, empty }: WidgetDashboardProps) {
  const [ extensions, setExtensions ] = useState<DashboardExtension[]>([]);
  const [ loading, setLoading ] = useState(true);
  const persona = usePersona();
  const [ collapsed, toggleCollapsed ] = useCollapsedGroups(point);

  useEffect(() => {
    loadExtensions(point)
      .then(loaded => setExtensions(loaded))
      .catch((err: unknown) => console.error(`Something went wrong loading the ${point} widgets`, err))
      .finally(() => setLoading(false));
  }, [point]);

  const groups = useMemo(() => extensions.filter(isWidgetGroup), [extensions]);
  const widgets = useMemo(() => extensions.filter(extension => !isWidgetGroup(extension)), [extensions]);

  // Only what belongs to the persona currently being worn. Filtered here rather than at load time
  // so that switching persona re-lays out the dashboard without fetching anything again.
  const slots = useMemo(
    () => groupWidgets(widgets, groups, extension => visibleInPersona(extension, persona)),
    [ widgets, groups, persona ]
  );

  if (!loading && slots.length === 0) {
    return <>{empty}</>;
  }

  return (
    <>
      <LoadingOverlay open={loading} />
      {/* Groups stand further apart than the widgets within them, so each reads as one block */}
      <Stack sx={{ gap: 3 }}>
        {
          slots.map(slot => (
            <WidgetGroupPanel
              // Prefixed, so that the ungrouped run is keyed apart from every group whatever the
              // groups are named
              key={slot.name === null ? "ungrouped" : `group:${slot.name}`}
              slot={slot}
              collapsed={collapsed.includes(slotId(slot))}
              onToggle={() => toggleCollapsed(slotId(slot))}
            />
          ))
        }
      </Stack>
    </>
  );
}

export default WidgetDashboard;
