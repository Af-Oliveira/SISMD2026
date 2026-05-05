"""
Generate the PNG charts referenced by report/REPORT.md from the raw
benchmark CSVs under results/<gc>/.

Run from the project root with the project venv:

    .\\.venv\\Scripts\\python.exe report\\charts\\generate_charts.py

Output files (overwritten on every run):

    report/charts/exec_time_by_impl.png
    report/charts/speedup_vs_threads.png
    report/charts/gc_pause_per_gc.png
    report/charts/speedup_per_size.png
    report/charts/heatmap_impl_size.png

The numbers are read live from the CSVs every time, so re-running
`gc-tuning/run_all.ps1` followed by this script keeps the report in
sync with the latest measurements.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

# ── Paths ──────────────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parents[2]
RESULTS_DIR = PROJECT_ROOT / "results"
OUT_DIR = PROJECT_ROOT / "report" / "charts"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Display order (Sequential first as the baseline; parallel impls follow).
IMPL_ORDER = ["Sequential", "ManualThread", "ThreadPool",
              "ForkJoin", "CompletableFuture"]
SIZE_ORDER = ["small_640x480", "medium_1280x720", "large_1920x1080"]
SIZE_LABELS = {
    "small_640x480": "640×480",
    "medium_1280x720": "1280×720",
    "large_1920x1080": "1920×1080",
}
GC_ORDER = ["g1", "parallel", "serial", "zgc"]
GC_LABELS = {"g1": "G1", "parallel": "Parallel",
             "serial": "Serial", "zgc": "ZGC"}

# Distinct, colourblind-friendly palette (Tableau 10).
COLOURS = {
    "Sequential":        "#7f7f7f",
    "ManualThread":      "#1f77b4",
    "ThreadPool":        "#ff7f0e",
    "ForkJoin":          "#2ca02c",
    "CompletableFuture": "#d62728",
    "g1":                "#2ca02c",
    "parallel":          "#1f77b4",
    "serial":            "#ff7f0e",
    "zgc":               "#d62728",
}

# Common matplotlib style.
plt.rcParams.update({
    "figure.dpi":      150,
    "savefig.dpi":     150,
    "savefig.bbox":    "tight",
    "font.size":       10,
    "axes.titlesize":  12,
    "axes.titleweight": "bold",
    "axes.labelsize":  10,
    "axes.spines.top":   False,
    "axes.spines.right": False,
    "axes.grid":         True,
    "axes.grid.axis":    "y",
    "grid.alpha":        0.25,
    "grid.linestyle":    "--",
})

# ── Data loading ───────────────────────────────────────────────────


def load_time(gc: str) -> pd.DataFrame:
    """Load results_time.csv for a given GC, returning a DataFrame."""
    csv = RESULTS_DIR / gc / "results_time.csv"
    df = pd.read_csv(csv)
    df["gc"] = gc
    return df


def load_gc(gc: str) -> pd.DataFrame:
    csv = RESULTS_DIR / gc / "results_gc.csv"
    df = pd.read_csv(csv)
    df["gc"] = gc
    return df


def best_per_impl_size(df: pd.DataFrame) -> pd.DataFrame:
    """Best (lowest) avgMs per (implementation, imageSize), with thread."""
    idx = df.groupby(["implementation", "imageSize"])["avgMs"].idxmin()
    return df.loc[idx].reset_index(drop=True)


# ── Chart 1: Exec time by impl (1920×1080, G1) ─────────────────────


def chart_exec_time_by_impl(df_g1: pd.DataFrame) -> Path:
    best = best_per_impl_size(df_g1)
    row = best[best["imageSize"] == "large_1920x1080"].set_index("implementation")
    values = [row.loc[i, "avgMs"] for i in IMPL_ORDER]
    threads = [int(row.loc[i, "threadCount"]) for i in IMPL_ORDER]

    fig, ax = plt.subplots(figsize=(8, 4.5))
    bars = ax.bar(IMPL_ORDER, values, color=[COLOURS[i] for i in IMPL_ORDER])
    for bar, v, t in zip(bars, values, threads):
        suffix = "" if t == 1 else f" (t={t})"
        ax.text(bar.get_x() + bar.get_width() / 2, v + 0.4,
                f"{v:.2f} ms{suffix}",
                ha="center", va="bottom", fontsize=9)
    ax.set_title("Execution time per implementation — 1920×1080, G1\n"
                 "(best across thread counts)")
    ax.set_ylabel("Avg time per call (ms)")
    ax.set_ylim(0, max(values) * 1.18)

    out = OUT_DIR / "exec_time_by_impl.png"
    fig.savefig(out)
    plt.close(fig)
    return out


# ── Chart 2: Speedup vs. threads (1920×1080, G1) ───────────────────


def chart_speedup_vs_threads(df_g1: pd.DataFrame) -> Path:
    df = df_g1[df_g1["imageSize"] == "large_1920x1080"].copy()
    seq = df[df["implementation"] == "Sequential"]["avgMs"].iloc[0]
    par = df[df["implementation"] != "Sequential"].copy()
    par["speedup"] = seq / par["avgMs"]

    fig, ax = plt.subplots(figsize=(8, 4.5))
    for impl in [i for i in IMPL_ORDER if i != "Sequential"]:
        sub = par[par["implementation"] == impl].sort_values("threadCount")
        ax.plot(sub["threadCount"], sub["speedup"],
                marker="o", linewidth=2, markersize=6,
                label=impl, color=COLOURS[impl])

    ax.axhline(1.0, color="#7f7f7f", linestyle=":", linewidth=1)
    ax.text(par["threadCount"].max(), 1.03, "Sequential baseline",
            ha="right", va="bottom", fontsize=8, color="#7f7f7f")

    ax.set_title("Speedup vs. thread count — 1920×1080, G1\n"
                 f"(T_seq = {seq:.2f} ms)")
    ax.set_xlabel("Thread count")
    ax.set_ylabel("Speedup vs. Sequential")
    ax.set_xticks(sorted(par["threadCount"].unique()))
    ax.set_ylim(0, par["speedup"].max() * 1.15)
    ax.legend(loc="lower right", frameon=False)

    out = OUT_DIR / "speedup_vs_threads.png"
    fig.savefig(out)
    plt.close(fig)
    return out


# ── Chart 3: Total GC time per GC ──────────────────────────────────


def chart_gc_pause_per_gc() -> Path:
    rows = []
    for gc in GC_ORDER:
        gc_df = load_gc(gc)
        rows.append({
            "gc": gc,
            "gcTimeMs": int(gc_df["gcTimeMs"].sum()),
            "gcCount":  int(gc_df["gcCount"].sum()),
        })
    summary = pd.DataFrame(rows)

    fig, ax = plt.subplots(figsize=(8, 4.5))
    bars = ax.bar([GC_LABELS[g] for g in summary["gc"]],
                  summary["gcTimeMs"],
                  color=[COLOURS[g] for g in summary["gc"]])
    for bar, t, c in zip(bars, summary["gcTimeMs"], summary["gcCount"]):
        ax.text(bar.get_x() + bar.get_width() / 2, t + max(summary["gcTimeMs"]) * 0.015,
                f"{t} ms\n({c} cycles)",
                ha="center", va="bottom", fontsize=9)

    ax.set_title("Cumulative GC time across the full sweep (lower is better)")
    ax.set_ylabel("Σ GC time (ms)")
    ax.set_ylim(0, summary["gcTimeMs"].max() * 1.25)

    out = OUT_DIR / "gc_pause_per_gc.png"
    fig.savefig(out)
    plt.close(fig)
    return out


# ── Chart 4: Speedup of best parallel impl per image size ──────────


def chart_speedup_per_size(df_g1: pd.DataFrame) -> Path:
    best = best_per_impl_size(df_g1)
    sizes = SIZE_ORDER
    speedups, winners = [], []
    for s in sizes:
        sub = best[best["imageSize"] == s].set_index("implementation")
        seq = sub.loc["Sequential", "avgMs"]
        parallel_only = sub.drop(index="Sequential")
        winner_impl = parallel_only["avgMs"].idxmin()
        winner_ms = parallel_only["avgMs"].min()
        speedups.append(seq / winner_ms)
        winners.append(winner_impl)

    fig, ax = plt.subplots(figsize=(8, 4.5))
    bars = ax.bar([SIZE_LABELS[s] for s in sizes],
                  speedups,
                  color=[COLOURS[w] for w in winners])
    for bar, sp, w in zip(bars, speedups, winners):
        ax.text(bar.get_x() + bar.get_width() / 2, sp + 0.05,
                f"{sp:.2f}× ({w})",
                ha="center", va="bottom", fontsize=9)

    ax.set_title("Speedup of fastest parallel impl vs. Sequential — G1")
    ax.set_xlabel("Image size")
    ax.set_ylabel("Speedup")
    ax.set_ylim(0, max(speedups) * 1.20)

    out = OUT_DIR / "speedup_per_size.png"
    fig.savefig(out)
    plt.close(fig)
    return out


# ── Chart 5: Heatmap impl × image size (G1, ms) ────────────────────


def chart_heatmap(df_g1: pd.DataFrame) -> Path:
    best = best_per_impl_size(df_g1)
    matrix = np.zeros((len(IMPL_ORDER), len(SIZE_ORDER)))
    for r, impl in enumerate(IMPL_ORDER):
        for c, size in enumerate(SIZE_ORDER):
            row = best[(best["implementation"] == impl)
                       & (best["imageSize"] == size)]
            matrix[r, c] = row["avgMs"].iloc[0]

    fig, ax = plt.subplots(figsize=(6.8, 4.5))
    im = ax.imshow(matrix, cmap="viridis_r", aspect="auto")

    ax.set_xticks(range(len(SIZE_ORDER)),
                  labels=[SIZE_LABELS[s] for s in SIZE_ORDER])
    ax.set_yticks(range(len(IMPL_ORDER)), labels=IMPL_ORDER)

    for r in range(len(IMPL_ORDER)):
        for c in range(len(SIZE_ORDER)):
            v = matrix[r, c]
            colour = "white" if v > matrix.max() * 0.55 else "black"
            ax.text(c, r, f"{v:.2f}", ha="center", va="center",
                    fontsize=10, color=colour)

    ax.set_title("Avg time per call (ms) — implementation × image size (G1)")
    cbar = fig.colorbar(im, ax=ax, fraction=0.04, pad=0.04)
    cbar.set_label("ms (lower = better)")

    # No grid for heatmaps.
    ax.grid(False)

    out = OUT_DIR / "heatmap_impl_size.png"
    fig.savefig(out)
    plt.close(fig)
    return out


# ── Main ───────────────────────────────────────────────────────────


def main() -> None:
    df_g1 = load_time("g1")
    produced = [
        chart_exec_time_by_impl(df_g1),
        chart_speedup_vs_threads(df_g1),
        chart_gc_pause_per_gc(),
        chart_speedup_per_size(df_g1),
        chart_heatmap(df_g1),
    ]
    print("Generated:")
    for p in produced:
        print(f"  {p.relative_to(PROJECT_ROOT)}")


if __name__ == "__main__":
    main()
