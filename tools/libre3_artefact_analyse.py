#!/usr/bin/env python3
"""
Libre 3 artefact-analyse (offline). Vervolg op libre3_filter_analyse.py.

Vraag: de Libre 3 geeft soms een korte, gladde piek (bv. +25 mg/dL in 5 min en 10 min later weer
terug) die een andere sensor (G6) niet ziet. Dat is waarschijnlijk een sensorartefact. Zo'n piek
mag geen "snelle stijging" worden in de 5-minuut zending naar AAPS. Dit script meet:

  1. Hoeveel van zulke pieken er zijn en hoe ze verschillen van echte stijgingen
     (snelheid, hoogte, duur).
  2. Per filter en per "poort" (gate) wat AAPS ziet bij die pieken (hoogte van de zending en of
     de helling boven de stijgdrempel komt), en wat de poort kost bij ECHTE stijgingen
     (later gezien, lager niveau in de eerste 10 min).

Poorten (werken op de 5-minuut zending, causaal):
  geen        : zending = filterwaarde (+ lead)
  cap<N>      : de zending mag per 5 min maar N mg/dL stijgen en haalt daarna in. Een echte stijging
                wordt zo een stap of twee later volledig; een korte piek wordt afgetopt op N.
  cap<N>h     : zelfde, maar de cap geldt alleen als de glucose daarvoor vlak was (|helling| < 0,5
                mg/dL/min over 15 min). Bij een al lopende stijging (maaltijd) geen cap.

Gebruik:
  python tools/libre3_artefact_analyse.py <map> --out <map>/resultaat_artefact
  (zelfde map als libre3_filter_analyse.py: libre3_minuut_*.csv, optioneel referentie_*.csv)

Nodig: python 3.9+, numpy, pandas.
"""
import argparse
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import libre3_filter_analyse as base  # noqa: E402


# ---------------------------------------------------------------------------------------------
# Pieken vinden in de ruwe 1-minuut data
# ---------------------------------------------------------------------------------------------
def find_spikes(t, g, min_rise=15.0, rise_min=8, back_min=12, back_frac=0.6):
    """Een piek = lokaal maximum dat >= min_rise boven het minimum van de 8 min ervoor ligt en
    binnen 12 min voor >= 60% terugzakt. Meerdere punten van dezelfde piek worden samengevoegd."""
    gap = base.near_gap_mask(t)
    n = len(t)
    found = []
    for i in range(rise_min, n - back_min):
        if gap[i]:
            continue
        w = g[max(0, i - rise_min): i + rise_min + 1]
        if g[i] < np.nanmax(w):
            continue
        j0 = i - rise_min + int(np.nanargmin(g[i - rise_min: i + 1]))
        rise = g[i] - g[j0]
        if rise < min_rise:
            continue
        after = np.nanmin(g[i: i + back_min + 1])
        if g[i] - after < back_frac * rise:
            continue
        rate5 = np.nanmax([(g[k] - g[k - 5]) / 5.0 for k in range(max(5, j0), i + 1)]) if i >= 5 else np.nan
        found.append({"t_peak": t[i], "t_start": t[j0], "base": g[j0], "peak": g[i], "rise": rise,
                      "rate5": rate5, "back": g[i] - after, "dur_min": (t[i] - t[j0]) / 60.0})
    merged = []
    for e in found:
        if merged and e["t_peak"] - merged[-1]["t_peak"] < 10 * 60:
            if e["peak"] > merged[-1]["peak"]:
                merged[-1] = e
        else:
            merged.append(e)
    return merged


def real_rise_stats(t, g, lo, rises):
    out = []
    for t0 in rises:
        m = (t >= t0 - 2 * 60) & (t <= t0 + 15 * 60)
        ii = np.where(m)[0]
        ii = ii[ii >= 5]
        if len(ii) == 0:
            continue
        r5 = max((g[k] - g[k - 5]) / 5.0 for k in ii)
        l0 = float(np.interp(t0, t, np.nan_to_num(lo, nan=0.0)))
        l30 = base.interp_valid(t, lo, np.array([t0 + 30 * 60]))[0]
        out.append({"t0": t0, "rate5": r5, "gain30": l30 - l0 if np.isfinite(l30) else np.nan})
    return out


# ---------------------------------------------------------------------------------------------
# Poorten op de 5-minuut zending
# ---------------------------------------------------------------------------------------------
def gate_grid(val, cap, only_flat):
    if cap is None:
        return val.copy()
    out = val.copy()
    prev = np.nan
    for k in range(len(val)):
        v = val[k]
        if not np.isfinite(v):
            prev = np.nan
            continue
        if np.isfinite(prev):
            flat_before = True
            if only_flat:
                a = k - 3
                if a >= 0 and np.isfinite(val[a]):
                    flat_before = abs(val[k - 1] - val[a]) / 15.0 < 0.5  # helling over de 15 min voor deze stap
            if flat_before and v - prev > cap:
                v = prev + cap
        out[k] = v
        prev = v
    return out


GATES = [("geen", None, False), ("cap8", 8.0, False), ("cap12", 12.0, False), ("cap16", 16.0, False),
         ("cap8h", 8.0, True), ("cap12h", 12.0, True)]
FILTERS = [("raw", {}, 0.0), ("ewma", {"a": 0.7}, 2.0), ("ls", {"span": 6}, 2.0), ("ewma", {"a": 0.5}, 4.0)]


def evaluate_gates(t, g, lo, so, spikes, rises, thr):
    rows = []
    for kind, params, lead in FILTERS:
        level, slope = base.FILTERS[kind](t, g, **params)
        fname = base.cand_name(kind, params) + (f" lead={lead:g}" if lead else "")
        for gname, cap, flat in GATES:
            amp, trig, amps_n = [], 0, 0
            d5, miss, under = [], 0, []
            for phase in range(5):
                tk, val = base.make_grid(t, level, slope, phase, lead, 15.0)
                vg = gate_grid(val, cap, flat)
                gs = base.grid_slope(tk, vg)
                for sp in spikes:
                    m = (tk >= sp["t_start"] - 5 * 60) & (tk <= sp["t_peak"] + 12 * 60) & np.isfinite(vg)
                    if not m.any():
                        continue
                    amp.append(float(np.max(vg[m]) - sp["base"]))
                    amps_n += 1
                    trig += int(np.nanmax(gs[m]) >= thr) if np.isfinite(gs[m]).any() else 0
                for t0 in rises:
                    d = base.detect_rise(tk, gs, t0, thr, 1)
                    d5.append(d)
                    m = (tk >= t0) & (tk <= t0 + 10 * 60) & np.isfinite(vg)
                    if m.any():
                        lo_k = base.interp_valid(t, lo, tk[m])
                        u = np.nanmax(lo_k - vg[m]) if np.isfinite(lo_k).any() else np.nan
                        under.append(u)
            d5 = np.array(d5, float)
            rows.append({
                "filter": fname, "poort": gname,
                "piek_hoogte_mgdl": float(np.mean(amp)) if amp else np.nan,
                "piek_als_stijging_%": 100.0 * trig / amps_n if amps_n else np.nan,
                "stijg_5min": float(np.nanmean(d5)) if np.isfinite(d5).any() else np.nan,
                "gemist_5min": float(np.sum(~np.isfinite(d5))) / 5.0,
                "achterstand_10min_mgdl": float(np.nanmean(under)) if under else np.nan,
            })
    return pd.DataFrame(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", help="map met libre3_minuut_*.csv")
    ap.add_argument("--out", help="uitvoermap")
    ap.add_argument("--rise-thr", type=float, default=1.0)
    ap.add_argument("--min-rise", type=float, default=15.0, help="min. hoogte van een piek in mg/dL [15]")
    args = ap.parse_args()
    t, g = base.load_libre(args.folder)
    out = args.out or os.path.join(args.folder, "resultaat_artefact")
    os.makedirs(out, exist_ok=True)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    pd.set_option("display.float_format", lambda x: f"{x:.2f}")

    lo, so = base.oracle(t, g)
    rises = base.find_rises(t, lo, so, args.rise_thr)
    spikes = find_spikes(t, g, args.min_rise)
    # pieken die samenvallen met een door de oracle erkende stijging tellen als echt, niet als artefact
    spike_set, overlap = [], 0
    for sp in spikes:
        if any(abs(r - sp["t_start"]) < 10 * 60 for r in rises):
            overlap += 1
        else:
            spike_set.append(sp)
    hours = len(t) / 60.0
    print(f"{hours:.1f} uur data. Pieken (op-en-neer): {len(spikes)}, waarvan {overlap} vallen samen met een "
          f"echte stijging -> {len(spike_set)} artefact-kandidaten ({len(spike_set) / (hours / 24):.1f}/dag)")
    print(f"Echte stijgingen (oracle): {len(rises)}")

    st = pd.DataFrame(spike_set)
    if len(st):
        st["tijd"] = pd.to_datetime(st["t_peak"], unit="s")  # zelfde klok als in de CSV (lokale tijd)
        print("\nARTEFACT-KANDIDATEN (tijd = lokale tijd):")
        print(st[["tijd", "base", "peak", "rise", "rate5", "back", "dur_min"]].to_string(index=False))
        st.to_csv(os.path.join(out, "artefact_kandidaten.csv"), sep=";", index=False)
    rs = pd.DataFrame(real_rise_stats(t, g, lo, rises))
    if len(rs) and len(st):
        print("\nVERGELIJKING snelheid (max stijging over 5 min, mg/dL per min):")
        print(f"  artefact-kandidaten: mediaan {st['rate5'].median():.2f}  p25 {st['rate5'].quantile(.25):.2f}  "
              f"p75 {st['rate5'].quantile(.75):.2f}  max {st['rate5'].max():.2f}")
        print(f"  echte stijgingen   : mediaan {rs['rate5'].median():.2f}  p25 {rs['rate5'].quantile(.25):.2f}  "
              f"p75 {rs['rate5'].quantile(.75):.2f}  max {rs['rate5'].max():.2f}")

    table = evaluate_gates(t, g, lo, so, spike_set, rises, args.rise_thr)
    print("\nPOORTEN (piek_hoogte = hoogste zending in de piek boven het beginniveau; "
          "piek_als_stijging = % pieken waarbij AAPS een stijging >= drempel ziet;\n"
          " stijg_5min = minuten na de echte start; achterstand = max. wat de zending in de eerste 10 min "
          "onder de oracle blijft)")
    print(table.to_string(index=False))
    table.to_csv(os.path.join(out, "poorten.csv"), sep=";", index=False)
    print("\nKlaar. Resultaten in:", out)


if __name__ == "__main__":
    main()
