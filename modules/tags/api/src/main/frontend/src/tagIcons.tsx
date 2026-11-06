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

import { type ReactElement } from "react";

import AssignmentReturnOutlined from "@mui/icons-material/AssignmentReturnOutlined";
import BlockOutlined from "@mui/icons-material/BlockOutlined";
import CancelOutlined from "@mui/icons-material/CancelOutlined";
import CheckCircleOutlined from "@mui/icons-material/CheckCircleOutlined";
import CheckOutlined from "@mui/icons-material/CheckOutlined";
import DescriptionOutlined from "@mui/icons-material/DescriptionOutlined";
import EditNoteOutlined from "@mui/icons-material/EditNoteOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import FactCheckOutlined from "@mui/icons-material/FactCheckOutlined";
import HighlightOffOutlined from "@mui/icons-material/HighlightOffOutlined";
import HourglassEmptyOutlined from "@mui/icons-material/HourglassEmptyOutlined";
import PendingOutlined from "@mui/icons-material/PendingOutlined";
import RateReviewOutlined from "@mui/icons-material/RateReviewOutlined";
import ReplyOutlined from "@mui/icons-material/ReplyOutlined";
import RuleOutlined from "@mui/icons-material/RuleOutlined";
import SendOutlined from "@mui/icons-material/SendOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import VisibilityOutlined from "@mui/icons-material/VisibilityOutlined";

// The icons a tag definition may name in its `icon` property, as elements shared by every chip.
// Each MUI icon is a component of its own, and only the ones listed here are bundled. A
// definition naming another icon needs it added here.
const TAG_ICONS: Record<string, ReactElement | undefined> = {
  AssignmentReturnOutlined: <AssignmentReturnOutlined />,
  BlockOutlined: <BlockOutlined />,
  CancelOutlined: <CancelOutlined />,
  CheckCircleOutlined: <CheckCircleOutlined />,
  CheckOutlined: <CheckOutlined />,
  DescriptionOutlined: <DescriptionOutlined />,
  EditNoteOutlined: <EditNoteOutlined />,
  EditOutlined: <EditOutlined />,
  FactCheckOutlined: <FactCheckOutlined />,
  HighlightOffOutlined: <HighlightOffOutlined />,
  HourglassEmptyOutlined: <HourglassEmptyOutlined />,
  PendingOutlined: <PendingOutlined />,
  RateReviewOutlined: <RateReviewOutlined />,
  ReplyOutlined: <ReplyOutlined />,
  RuleOutlined: <RuleOutlined />,
  SendOutlined: <SendOutlined />,
  TaskAltOutlined: <TaskAltOutlined />,
  VisibilityOutlined: <VisibilityOutlined />,
};

// The icon element a definition names, or undefined if the name is absent or not listed. hasOwn
// keeps "constructor" from returning an inherited Object.prototype member.
export function tagIcon(name?: string): ReactElement | undefined {
  return name !== undefined && Object.hasOwn(TAG_ICONS, name) ? TAG_ICONS[name] : undefined;
}
