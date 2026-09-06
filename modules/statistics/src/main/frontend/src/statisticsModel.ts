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

// What a metric says, and how to read it out of the endpoint's JSON. No React and no fetch: pure
// functions of their arguments, so the formatting rules - which are the part a reader will argue with -
// can be tested without rendering anything.

/** Where the computed metrics are served from. */
export const STATISTICS_PATH = "/Statistics.json";

// The charts live at the path of the content they are about. A route of their own would need a
// resource behind it, and the only synthetic routes the shell serves are the administration ones -
// which these are not, since a metric can be public.
/** Where the charts live in the application. */
export const STATISTICS_ROUTE = "/Statistics";

/** One named subset of a metric's measurements: a reviewer, a study type, or a month. */
export interface Slice {
  key: string;
  /** Null when the subset held nothing measurable, which is not the same as zero. */
  value: number | null;
  sampleSize: number;
}

/** What one metric currently says. */
export interface Metric {
  name: string;
  label: string;
  description?: string;
  category?: string;
  unit?: string;
  /** What follows the number, in ordinary weight beside it: "/ request", "to approval". */
  qualifier?: string;
  /** Whether the label is worth showing above the number, for a figure that cannot say what it is. */
  prominentLabel: boolean;
  /** Null when nothing could be measured at all. */
  value: number | null;
  sampleSize: number;
  breakdown: Slice[];
  series: Slice[];
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const text = (value: unknown): string => (typeof value === "string" ? value : "");

const optionalText = (value: unknown): string | undefined =>
  typeof value === "string" && value.length > 0 ? value : undefined;

const number = (value: unknown): number => (typeof value === "number" ? value : 0);

const optionalNumber = (value: unknown): number | null =>
  typeof value === "number" ? value : null;

const readSlices = (value: unknown): Slice[] =>
  (Array.isArray(value) ? value : [])
    .filter(isRecord)
    .map(slice => ({
      key: text(slice.key),
      value: optionalNumber(slice.value),
      sampleSize: number(slice.sampleSize),
    }));

/**
 * When the endpoint says the figures were worked out, or null if they never have been.
 *
 * They are computed on a schedule rather than per request, so every page showing them owes its reader
 * this date - a stale number nobody can date is the one that misleads.
 */
export function readComputedAt(payload: unknown): Date | null {
  if (!isRecord(payload) || typeof payload.computedAt !== "string") {
    return null;
  }
  const when = new Date(payload.computedAt);
  return Number.isNaN(when.getTime()) ? null : when;
}

/** Reads the endpoint's answer, keeping only the metrics that are shaped like metrics. */
export function readMetrics(payload: unknown): Metric[] {
  if (!isRecord(payload) || !Array.isArray(payload.metrics)) {
    return [];
  }
  return payload.metrics
    .filter(isRecord)
    .filter(metric => typeof metric.name === "string" && typeof metric.label === "string")
    .map(metric => ({
      name: text(metric.name),
      label: text(metric.label),
      description: optionalText(metric.description),
      category: optionalText(metric.category),
      unit: optionalText(metric.unit),
      qualifier: optionalText(metric.qualifier),
      prominentLabel: metric.prominentLabel === true,
      value: optionalNumber(metric.value),
      sampleSize: number(metric.sampleSize),
      breakdown: readSlices(metric.breakdown),
      series: readSlices(metric.series),
    }));
}

/**
 * A measured number as a reader should see it: at most one decimal, because a median expressed to
 * four places claims a precision the sample does not have.
 *
 * A metric with nothing to measure reads as a dash rather than as zero - "no requests were authorized"
 * and "requests were authorized in zero days" are different statements, and only one of them is true.
 */
export function formatValue(value: number | null, unit?: string): string {
  if (value === null) {
    return "—";
  }
  const rounded = Math.abs(value) >= 100 ? Math.round(value) : Math.round(value * 10) / 10;
  const shown = rounded.toLocaleString(undefined, { maximumFractionDigits: 1 });
  if (unit === undefined) {
    return shown;
  }
  return unit === "%" ? `${shown}%` : `${shown} ${unit}`;
}

const startOfDay = (when: Date): number =>
  new Date(when.getFullYear(), when.getMonth(), when.getDate()).getTime();

/**
 * When the figures were worked out, said the way somebody would say it out loud.
 *
 * Counted in local calendar days rather than in elapsed hours: "yesterday at 2:30 AM" is what a reader
 * checks their own memory against, and a figure computed 23 hours ago can be either day. Which also
 * keeps it clear of the usual trap - a UTC date is a different day from a local one for a quarter of
 * every day.
 */
export function formatComputedAt(when: Date, now: Date = new Date()): string {
  const time = when.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  const days = Math.round((startOfDay(now) - startOfDay(when)) / 86_400_000);
  if (days === 0) {
    return `today at ${time}`;
  }
  if (days === 1) {
    return `yesterday at ${time}`;
  }
  const date = when.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
  return `on ${date} at ${time}`;
}

/** How many subjects a number rests on, said the way a caption would say it. */
export function formatSample(sampleSize: number): string {
  return sampleSize === 1 ? "1 request" : `${sampleSize.toLocaleString()} requests`;
}

/**
 * A `yyyy-MM` key as a month a chart axis can carry.
 *
 * The year is written out. A two-digit year next to a short month reads as a day - "Sep 25" is a date
 * to most people, and a series running from Sep 2025 to Sep 2026 then looks like two days in September.
 */
export function formatMonth(key: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(key);
  if (match === null) {
    return key;
  }
  const month = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return month.toLocaleDateString(undefined, { month: "short", year: "numeric" });
}

/** The metrics grouped the way they are meant to be shown, keeping the order the endpoint sent. */
export function byCategory(metrics: Metric[]): { category: string; metrics: Metric[] }[] {
  const groups = new Map<string, Metric[]>();
  metrics.forEach(metric => {
    const category = metric.category ?? "Other";
    const existing = groups.get(category);
    if (existing === undefined) {
      groups.set(category, [metric]);
    } else {
      existing.push(metric);
    }
  });
  return Array.from(groups, ([category, grouped]) => ({ category, metrics: grouped }));
}
