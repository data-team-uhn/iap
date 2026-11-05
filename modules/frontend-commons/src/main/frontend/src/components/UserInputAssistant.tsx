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

import { useState, useEffect, type ReactNode } from "react";

import EmojiObjectsIcon from "@mui/icons-material/EmojiObjects";
import WarningIcon from "@mui/icons-material/Warning";
import {
  Avatar,
  Button,
  Card,
  CardActions,
  CardContent,
  CardHeader,
  ClickAwayListener,
  Fade,
  Popper,
} from "@mui/material";

type UserInputAssistantVariant = 'hint' | 'hint-secondary' | 'success' | 'info' | 'warning' | 'error';

interface UserInputAssistantProps {
  anchorEl?: HTMLElement | null;
  title: string;
  variant?: UserInputAssistantVariant;
  children?: ReactNode;
  actionLabel?: string;
  onAction?: () => void;
  onIgnore?: () => void;
  onClickAway?: (event: MouseEvent | TouchEvent) => void;
}

// The palette color of each variant's border, arrow and avatar
const accents = {
  "hint": "primary",
  "hint-secondary": "secondary",
  "success": "success",
  "info": "info",
  "warning": "warning",
  "error": "error",
} as const;

// A card beside an input that suggests something to the user as they type, such as pressing Enter
// after each value rather than typing commas. It sits to the right of the input in a window wider
// than 750 pixels, and below it otherwise. Once dismissed, it stays hidden until it is mounted again.
//
// Required props:
// title: the card's title.
//
// Optional props:
// anchorEl: the element the card points at; nothing is shown without it.
// variant: the accent color and icon, `hint` by default:
//   *  hint: the primary color, a lightbulb.
//   *  hint-secondary: the secondary color, a lightbulb.
//   *  success, info: their own color, a lightbulb.
//   *  warning, error: their own color, a warning sign.
// actionLabel: the label of the button for the suggested action; no button without it.
// onAction: runs the suggested action; no button without it.
// onIgnore: adds an "Ignore for now" button, which hides the card and calls it. Without it, the
//   button says "Got it!" and only hides the card.
// onClickAway: called on a click outside the card.
// children: the message.
//
// Sample usage:
//<UserInputAssistant
//  anchorEl={input}
//  variant="hint-secondary"
//  title="Separator detected"
//  actionLabel="Separate and add"
//  onAction={separateAndAdd}
//  onIgnore={disableSeparatorDetection}
//  >
//  Don't use comma, press ENTER!
//</UserInputAssistant>
//

// ClickAwayListener requires a handler, and `onClickAway` is optional
function noop() { /* intentionally empty */ }

function UserInputAssistant(props: UserInputAssistantProps) {
  const {
    anchorEl,
    variant = 'hint',
    title,
    children,
    actionLabel,
    onAction,
    onIgnore,
    onClickAway
  } = props;

  const [ enabled, setEnabled ] = useState(true);

  const [ placement, setPlacement ] = useState<"right" | "bottom">("right");

  useEffect(() => {
    function handleResize() {
      setPlacement(window.innerWidth > 750 ? "right" : "bottom");
    }
    handleResize();
    window.addEventListener('resize', handleResize);
    return (() => {
      window.removeEventListener('resize', handleResize);
    });
  }, []);

  return (enabled ?
    <ClickAwayListener onClickAway={onClickAway ?? noop}>
      <Popper
        open={!!anchorEl}
        anchorEl={anchorEl}
        placement={placement}
        transition
        modifiers={[
          {
            name: 'flip',
            enabled: true
          }
        ]}
      >
        {({ TransitionProps }) => (
          <Fade {...TransitionProps} timeout={350}>
            <Card
              sx={theme => {
                const accent = (theme.vars ?? theme).palette[accents[variant]].main;
                return {
                  maxWidth: "375px",
                  border: `2px solid ${accent}`,
                  "& .MuiAvatar-root": {
                    background: accent,
                  },
                  ...(placement === "right" ? {
                    marginLeft: theme.spacing(2),
                    "&:before": {
                      content: "''",
                      display: "block",
                      borderTop: `${theme.spacing(2)} solid transparent`,
                      borderBottom: `${theme.spacing(2)} solid transparent`,
                      borderRight: `${theme.spacing(2)} solid ${accent}`,
                      position: "absolute",
                      left: 0,
                      top: "50%",
                      marginTop: theme.spacing(-2),
                    },
                  } : {
                    margin: theme.spacing(3, 4, 0),
                  }),
                };
              }}
            >
              <CardHeader
                avatar={<Avatar>
                  {
                    ['warning', 'error'].includes(variant) ?
                      <WarningIcon/> : <EmojiObjectsIcon />
                  }
                </Avatar>}
                title={title}
                slotProps={{ title: { variant: "h6" } }}
              />
              <CardContent>
                { children }
              </CardContent>
              <CardActions>
                { actionLabel && onAction &&
            <Button variant="outlined" onClick={onAction}>{actionLabel}</Button>
                }
                { onIgnore ?
                  <Button variant="outlined" onClick={() => {setEnabled(false); onIgnore();}}>Ignore for now</Button>
                  :
                  <Button variant="outlined" onClick={() => setEnabled(false)}>Got it!</Button>
                }
              </CardActions>
            </Card>
          </Fade>
        )}
      </Popper>
    </ClickAwayListener>
    : null);
}

export default UserInputAssistant;
