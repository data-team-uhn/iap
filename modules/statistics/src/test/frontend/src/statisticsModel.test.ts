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
  byCategory, formatMonth, formatSample, formatValue, readMetrics,
} from "@iap/statistics/statisticsModel";

describe("readMetrics", () => {
  it("reads a metric with everything it can carry", () => {
    const metrics = readMetrics({
      metrics: [{
        name: "timeToAuth", label: "Time to authorization", description: "How long",
        category: "Turnaround", unit: "days", qualifier: "to approval", prominentLabel: true,
        value: 32.5, sampleSize: 120,
        breakdown: [{ key: "achen", value: 30, sampleSize: 80 }],
        series: [{ key: "2026-08", value: 31, sampleSize: 60 }],
      }],
    });

    expect(metrics).toHaveLength(1);
    expect(metrics[0]).toMatchObject({
      name: "timeToAuth", label: "Time to authorization", unit: "days", value: 32.5, sampleSize: 120,
      qualifier: "to approval", prominentLabel: true,
    });
    expect(metrics[0].breakdown[0]).toEqual({ key: "achen", value: 30, sampleSize: 80 });
    expect(metrics[0].series[0]).toEqual({ key: "2026-08", value: 31, sampleSize: 60 });
  });

  it("keeps an unmeasured value as unmeasured", () => {
    const [metric] = readMetrics({ metrics: [{ name: "bare", label: "Bare", value: null,
      breakdown: [{ key: "nobody", value: null }] }] });

    expect(metric.value).toBeNull();
    expect(metric.sampleSize).toBe(0);
    expect(metric.breakdown[0].value).toBeNull();
    expect(metric.description).toBeUndefined();
    expect(metric.category).toBeUndefined();
    expect(metric.unit).toBeUndefined();
    expect(metric.qualifier).toBeUndefined();
    expect(metric.prominentLabel).toBe(false);
  });

  it("keeps only what is shaped like a metric", () => {
    expect(readMetrics({ metrics: [{ label: "no name" }, { name: "no label" }, "text", null] }))
      .toHaveLength(0);
    expect(readMetrics({ metrics: [{ name: "n", label: "l", breakdown: "not a list" }] })[0].breakdown)
      .toEqual([]);
  });

  it("reads anything else as no metrics at all", () => {
    expect(readMetrics(null)).toEqual([]);
    expect(readMetrics({})).toEqual([]);
    expect(readMetrics({ metrics: "text" })).toEqual([]);
  });
});

describe("formatValue", () => {
  // A median expressed to four places claims a precision the sample does not have
  it("rounds to one decimal, and to none once the number is large", () => {
    expect(formatValue(32.4567, "days")).toBe("32.5 days");
    expect(formatValue(1234.56, "fields")).toBe("1,235 fields");
  });

  it("puts a percentage against its number and everything else after a space", () => {
    expect(formatValue(59.44, "%")).toBe("59.4%");
    expect(formatValue(1.2, "issues")).toBe("1.2 issues");
    expect(formatValue(7)).toBe("7");
  });

  // Zero and "nobody measured anything" are different statements, and only one of them is true
  it("shows an unmeasured value as a dash rather than as zero", () => {
    expect(formatValue(null, "days")).toBe("—");
    expect(formatValue(0, "days")).toBe("0 days");
  });
});

describe("formatSample", () => {
  it("counts the requests a number rests on", () => {
    expect(formatSample(1)).toBe("1 request");
    expect(formatSample(0)).toBe("0 requests");
    expect(formatSample(1200)).toBe("1,200 requests");
  });
});

describe("formatMonth", () => {
  it("shortens a month key for an axis", () => {
    expect(formatMonth("2026-08")).toMatch(/Aug/);
  });

  it("leaves anything that is not a month alone", () => {
    expect(formatMonth("whenever")).toBe("whenever");
  });
});

describe("byCategory", () => {
  it("groups the metrics without reordering them", () => {
    const grouped = byCategory(readMetrics({ metrics: [
      { name: "a", label: "A", category: "Turnaround" },
      { name: "b", label: "B", category: "Quality" },
      { name: "c", label: "C", category: "Turnaround" },
      { name: "d", label: "D" },
    ] }));

    expect(grouped.map(group => group.category)).toEqual(["Turnaround", "Quality", "Other"]);
    expect(grouped[0].metrics.map(metric => metric.name)).toEqual(["a", "c"]);
  });
});
