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

// The JSON of one extension on the dashboard's point, with its `asset:` properties loaded.
type WidgetExtension = DashboardExtension;

// The props that the dashboard passes to each rendered widget.
interface WidgetContentProps {
  extension: WidgetExtension;
}

// How many columns each `ext:widgetWidth` value asks for. The span is cut to the columns the grid
// has at each breakpoint, so `full` fills the row. CSS Grid adds columns for a span wider than the
// grid.
const WIDTH_SPAN: Record<string, number> = { normal: 1, wide: 2, full: 3 };

// A React key for the widget itself, not for its position. Switching persona changes which widgets
// are shown, and a key by index would remount a widget that only moved, losing its state.
function widgetKey(widget: WidgetExtension, index: number): string {
  return (widget["@path"] as string | undefined)
    ?? (widget["ext:name"] as string | undefined)
    ?? `widget-${index}`;
}

// Which groups of a dashboard the viewer has collapsed, kept in this browser's local storage, so the
// dashboard looks the same when they come back to it. A group is kept by its node name. The widgets
// of no group are kept by the empty string, which cannot be a node name. Without storage, every group
// starts expanded.
const collapsedStorageKey = (point: string) => `iap.widgetDashboard.${point}.collapsed`;
const slotId = (slot: WidgetGroupSlot) => slot.name ?? "";

const readCollapsed = (point: string): string[] => {
  try {
    const stored: unknown = JSON.parse(window.localStorage.getItem(collapsedStorageKey(point)) ?? "[]");
    return Array.isArray(stored) ? stored.filter((id): id is string => typeof id === "string") : [];
  } catch {
    // No storage, or an unreadable value: every group starts expanded
    return [];
  }
};

const writeCollapsed = (point: string, ids: string[]) => {
  try {
    window.localStorage.setItem(collapsedStorageKey(point), JSON.stringify(ids));
  } catch {
    // No storage: the choice lasts until the page is left
  }
};

// The collapsed groups of the given dashboard, and a toggle for one of them. They are kept per point,
// so a dashboard given another point reads that point's choices.
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

// One run of widgets in a grid of one to three columns, each in its Widget frame. The number of
// columns follows the number of widgets in the run.
function WidgetGrid({ widgets }: { widgets: WidgetExtension[] }) {
  const { sm: smColumns, lg: lgColumns } = gridColumns(widgets.length);

  return (
    <Box
      sx={{
        display: "grid",
        gap: 2,
        // Each column gets an equal share of the width, however wide its widgets' content is. A plain
        // `1fr` column grows to fit its widest widget, which can make the page wider than a phone's
        // screen.
        gridTemplateColumns: {
          xs: "minmax(0, 1fr)",
          sm: `repeat(${smColumns}, minmax(0, 1fr))`,
          lg: `repeat(${lgColumns}, minmax(0, 1fr))`,
        },
      }}
    >
      {
        widgets.map((widget, index) => {
          const WidgetContent = widget["ext:render"] as ComponentType<WidgetContentProps>;
          const span = WIDTH_SPAN[(widget["ext:widgetWidth"] as string | undefined) ?? "normal"] ?? 1;
          const title = (widget["ext:name"] as string | undefined) ?? "";
          const actionLabel = widget["ext:actionLabel"] as string | undefined;
          // `ext:targetURL` counts only with `ext:actionLabel`, for the title link as well
          const targetURL = actionLabel ? widget["ext:targetURL"] as string | undefined : undefined;
          const action = targetURL
            ? (
              <Button
                size="small"
                variant="text"
                endIcon={<ArrowForwardIcon />}
                component={RouterLink}
                to={targetURL}
                // Several widgets may share a verb such as "Manage", so the accessible name adds the
                // title. A screen reader's list of links reads the button away from its header.
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
                minWidth: 0,
                gridColumn: {
                  xs: "span 1",
                  sm: `span ${Math.min(span, smColumns)}`,
                  lg: `span ${Math.min(span, lgColumns)}`,
                },
              }}
            >
              <Widget
                title={title}
                // The title leads to the same place as the action
                titleHref={targetURL}
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

// One widget group: a title across the row that collapses and expands the group's widgets, and
// counts them while they are hidden. A run with no title is only its grid. Either way the grid keeps
// its place in the tree, so a persona change that adds or removes the title does not remount the
// widgets.
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
        {/* A collapsed group's widgets are unmounted, so they fetch nothing */}
        <Collapse in={open} timeout="auto" unmountOnExit>
          <WidgetGrid widgets={slot.widgets} />
        </Collapse>
      </Box>
    </Box>
  );
}

interface WidgetDashboardProps {
  // The node name of the extension point whose extensions are the widgets, such as
  // "DashboardWidget".
  point: string;
  // Shown instead of the dashboard when no widget is visible, such as when the user can read none.
  // Without it, an empty dashboard renders nothing.
  empty?: ReactNode;
}

// A dashboard of the widgets on the given extension point, in a grid of one to three columns. Each
// widget sits in a Widget frame, titled by its `ext:name`, with `ext:subtitle` under the title. These
// optional properties change the frame:
//   - `ext:widgetWidth`: `normal`, `wide` or `full`, the number of columns the widget spans.
//   - `ext:widgetEmphasis`: puts the widget on a tinted surface.
//   - `ext:widgetBorderless`: drops the widget's fill, so it sits on the page.
//   - `ext:widgetHideHeader`: leaves out the title, subtitle and action, for a widget that draws
//     its own.
//   - `ext:actionLabel` with `ext:targetURL`: a text button with a forward arrow in the header,
//     leading to that in-app path. The title links there too. Both must be set. The label is best a
//     single verb for what the tool behind the widget does, such as "Manage" or "Triage".
//   - `ext:personas`: the personas the widget is shown to; all of them if absent.
//   - `ext:widgetGroup`: the node name of the widget group it is listed under.
//
// Widgets are filed under widget groups, which collapse, as widgetDashboardModel.ts describes.
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

  // Filtered by the current persona here, not at load time, so switching persona fetches nothing
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
      <Stack sx={{ gap: 3 }}>
        {
          slots.map(slot => (
            <WidgetGroupPanel
              // Prefixed, so no group's name can match the key of the widgets of no group
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
