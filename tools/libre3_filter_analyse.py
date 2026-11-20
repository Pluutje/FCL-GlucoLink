#!/usr/bin/env python3
"""
Libre 3 filter-analyse (offline).

Doel: een filter vinden voor de 1-minuut Libre 3 data waarmee de 5-minuut zending naar AAPS
  1. een stijging zo vroeg mogelijk (maar betrouwbaar) laat zien, en
  2. de afvlakking naar een piek zo vroeg mogelijk laat zien.

Invoer (map met de CSV's uit FCLGlucoLink, bv. via `adb pull .../files/log/`):
  libre3_minuut_<datum>.csv   ontvangen;gemeten;mgdl;trend_mgdl_per_min;bron      (1 minuut)
  referentie_<datum>.csv      tijd;slot;sensor;ruw_mgdl;gekalibreerd_mgdl;gefilterd_mgdl;trend (5 minuut)
  vingerprikken.csv           (optioneel) tijd;waarde      (mmol/L of mg/dL, wordt herkend)
                              tijd als "2026-11-20 12:15" of "2026-11-20 12:15:00"

Gebruik:
  python tools/libre3_filter_analyse.py D:\\logs\\libre3 --out D:\\logs\\libre3\\resultaat
  python tools/libre3_filter_analyse.py --demo            (synthetische data, om het script te testen)

Hoe het werkt (kort):
  * "Oracle" = een gladde schatting die NAAR VOREN en ACHTER kijkt (niet bruikbaar in de app).
    Die geeft het beste mogelijke beeld van wat de sensor echt deed. Hij is de maatstaf.
  * Elk filter werkt "causaal" (ziet alleen het verleden), zoals in de app.
  * Per filter meten we: ruis, vertraging (lag), hoe laat een stijging wordt gezien, hoe laat de
    afvlakking voor de piek wordt gezien, en het aantal valse alarmen per dag.
  * Dat doen we op de 1-minuut data zelf EN op wat AAPS ziet: de 5-minuut zending (alle 5 fasen
    van het 5-minuut raster, want we weten niet op welke seconde AAPS' raster valt).
  * De referentie (CareSens/G6) wordt met dezelfde regels gemeten, zodat je de winst ziet.

Nodig: python 3.9+, numpy, pandas. Voor plaatjes ook matplotlib (optioneel).
"""
import argparse
import glob
import math
import os
import sys

import numpy as np
import pandas as pd

MMOL = 18.016
EPOCH = pd.Timestamp("1970-01-01")


# ---------------------------------------------------------------------------------------------
# Inlezen
# ---------------------------------------------------------------------------------------------
def to_seconds(series):
    return (pd.to_datetime(series) - EPOCH).dt.total_seconds().to_numpy(dtype=float)


def load_libre(folder):
    files = sorted(glob.glob(os.path.join(folder, "*libre3_minuut_*.csv")))
    if not files:
        sys.exit("Geen libre3_minuut_*.csv gevonden in " + folder)
    df = pd.concat([pd.read_csv(f, sep=";") for f in files], ignore_index=True)
    df = df.dropna(subset=["gemeten", "mgdl"]).copy()
    df["t"] = to_seconds(df["gemeten"])
    df = df.drop_duplicates("t").sort_values("t")
    return df["t"].to_numpy(), df["mgdl"].to_numpy(dtype=float)


def load_reference(folder):
    files = sorted(glob.glob(os.path.join(folder, "*referentie_*.csv")))
    if not files:
        return {}
    df = pd.concat([pd.read_csv(f, sep=";") for f in files], ignore_index=True)
    df["t"] = to_seconds(df["tijd"])
    out = {}
    for sensor, grp in df.groupby("sensor"):
        grp = grp.drop_duplicates("t").sort_values("t")
        out[sensor] = grp
    return out


def load_fingersticks(folder):
    path = os.path.join(folder, "vingerprikken.csv")
    if not os.path.exists(path):
        return None
    df = pd.read_csv(path, sep=";")
    df.columns = [c.strip().lower() for c in df.columns]
    vcol = "waarde" if "waarde" in df.columns else df.columns[1]
    df["t"] = to_seconds(df[df.columns[0]])
    v = df[vcol].astype(str).str.replace(",", ".").astype(float)
    df["mgdl"] = np.where(v < 40, v * MMOL, v)  # kleiner dan 40 = mmol/L
    return df[["t", "mgdl"]].sort_values("t")


def make_demo():
    """Synthetische dag: 3 maaltijden, 1 hypo-herstel, ruis en een paar gaten + 5-min referentie."""
    rng = np.random.default_rng(7)
    t0 = (pd.Timestamp("2026-11-21 00:00:00") - EPOCH).total_seconds()
    t = t0 + np.arange(0, 24 * 3600, 60.0)
    minutes = np.arange(len(t)) / 1.0
    g = np.full(len(t), 105.0)

    def bump(center, amp, rise, fall):
        x = minutes - center
        y = np.where(x < 0, np.exp(-(x / rise) ** 2), np.exp(-(x / fall) ** 2))
        return amp * y
    for c, a in ((450, 90), (780, 110), (1130, 80), (300, -35)):
        g += bump(c, a, 22, 55) if a > 0 else bump(c, a, 15, 25)
    noise = np.zeros(len(t))
    for i in range(1, len(t)):
        noise[i] = 0.6 * noise[i - 1] + rng.normal(0, 1.6)
    meas = g + noise
    keep = np.ones(len(t), bool)
    for gap in (200, 610, 900):
        keep[gap:gap + 2] = False
    libre = (t[keep], np.round(meas[keep]))
    # referentie: 5 min, vertraagd (8 min) en vlak gefilterd
    ref_t = t[::5]
    lagged = np.interp(ref_t - 8 * 60, t, g) + rng.normal(0, 2.0, len(ref_t))
    ref = pd.DataFrame({"t": ref_t, "slot": "B", "sensor": "DEMO_REF", "ruw_mgdl": lagged,
                        "gekalibreerd_mgdl": lagged, "gefilterd_mgdl": pd.Series(lagged).ewm(alpha=0.5).mean()})
    return libre, {"DEMO_REF": ref}, None


# ---------------------------------------------------------------------------------------------
# Causale filters. Elk filter geeft (level, slope[mg/dL per minuut]) per meting.
# ---------------------------------------------------------------------------------------------
def back_slope(t, level, span_min=3.0):
    """Helling uit het verschil van het gefilterde signaal over span_min minuten terug."""
    n = len(t)
    s = np.full(n, np.nan)
    for i in range(n):
        j = np.searchsorted(t, t[i] - span_min * 60 + 30, side="right") - 1
        if j >= 0 and t[i] - t[j] >= (span_min - 1) * 60 and np.isfinite(level[j]) and np.isfinite(level[i]):
            s[i] = (level[i] - level[j]) / ((t[i] - t[j]) / 60.0)
    return s


def f_raw(t, g, **_):
    return g.copy(), back_slope(t, g)


def f_med(t, g, n=3, **_):
    out = np.empty(len(t))
    for i in range(len(t)):
        j = np.searchsorted(t, t[i] - (n * 60 + 30), side="left")
        out[i] = np.median(g[max(j, i - n + 1):i + 1])
    return out, back_slope(t, out)


def f_ma(t, g, span=5, **_):
    out = np.empty(len(t))
    for i in range(len(t)):
        j = np.searchsorted(t, t[i] - (span * 60 - 30), side="left")
        out[i] = g[j:i + 1].mean()
    return out, back_slope(t, out)


def f_ewma(t, g, a=0.3, **_):
    out = np.empty(len(t))
    out[0] = g[0]
    for i in range(1, len(t)):
        dt = (t[i] - t[i - 1]) / 60.0
        if dt > 5:
            out[i] = g[i]
        else:
            k = 1 - (1 - a) ** dt
            out[i] = out[i - 1] + k * (g[i] - out[i - 1])
    return out, back_slope(t, out)


def f_holt(t, g, a=0.5, b=0.15, **_):
    n = len(t)
    lvl = np.empty(n)
    sl = np.zeros(n)
    lvl[0] = g[0]
    for i in range(1, n):
        dt = (t[i] - t[i - 1]) / 60.0
        if dt > 5:
            lvl[i], sl[i] = g[i], 0.0
            continue
        pred = lvl[i - 1] + sl[i - 1] * dt
        lvl[i] = pred + (1 - (1 - a) ** dt) * (g[i] - pred)
        sl[i] = sl[i - 1] + (1 - (1 - b) ** dt) * ((lvl[i] - lvl[i - 1]) / dt - sl[i - 1])
    return lvl, sl


def f_ls(t, g, span=8, hl=None, **_):
    """Lopende kleinste-kwadraten lijn over de laatste `span` minuten. Eindpunt = level."""
    n = len(t)
    lvl = g.copy()
    sl = np.full(n, np.nan)
    for i in range(n):
        j = np.searchsorted(t, t[i] - span * 60, side="left")
        if i - j + 1 < 3:
            continue
        x = (t[j:i + 1] - t[i]) / 60.0
        y = g[j:i + 1]
        w = np.ones_like(x) if hl is None else 0.5 ** (-x / hl)
        sw, swx, swy = w.sum(), (w * x).sum(), (w * y).sum()
        swxx, swxy = (w * x * x).sum(), (w * x * y).sum()
        den = sw * swxx - swx * swx
        if den <= 1e-9:
            continue
        slope = (sw * swxy - swx * swy) / den
        sl[i] = slope
        lvl[i] = (swy - slope * swx) / sw
    return lvl, sl


def f_kalman(t, g, q=0.1, r=3.0, adapt=1.0, **_):
    """Lokaal-lineair-trend Kalman filter. q = onzekerheid van de versnelling (mg/dL per min^2),
    r = meetruis (mg/dL). adapt > 1: bij een grote verrassing wordt q tijdelijk groter (snelle
    reactie op echte veranderingen, rustig bij ruis)."""
    n = len(t)
    lvl = np.empty(n)
    sl = np.zeros(n)
    x = np.array([g[0], 0.0])
    P = np.diag([r * r, 1.0])
    lvl[0] = g[0]
    scale = 1.0
    for i in range(1, n):
        dt = (t[i] - t[i - 1]) / 60.0
        if dt > 5:
            x = np.array([g[i], 0.0])
            P = np.diag([r * r, 1.0])
            lvl[i], sl[i] = g[i], 0.0
            scale = 1.0
            continue
        F = np.array([[1.0, dt], [0.0, 1.0]])
        G = np.array([[dt * dt / 2.0], [dt]])
        Q = (q * q * scale) * (G @ G.T)
        x = F @ x
        P = F @ P @ F.T + Q
        S = P[0, 0] + r * r
        nu = g[i] - x[0]
        K = P[:, 0] / S
        x = x + K * nu
        P = P - np.outer(K, P[0, :])
        lvl[i], sl[i] = x[0], x[1]
        scale = adapt if (adapt > 1.0 and abs(nu) / math.sqrt(S) > 2.0) else 1.0
    return lvl, sl


FILTERS = {"raw": f_raw, "med": f_med, "ma": f_ma, "ewma": f_ewma, "holt": f_holt, "ls": f_ls, "kalman": f_kalman}


def candidates():
    c = [("raw", {})]
    c += [("med", {"n": n}) for n in (3, 5)]
    c += [("ma", {"span": 5})]
    c += [("ewma", {"a": a}) for a in (0.2, 0.3, 0.4, 0.5, 0.7)]
    c += [("holt", {"a": a, "b": b}) for a, b in ((0.3, 0.1), (0.5, 0.15), (0.5, 0.3), (0.7, 0.3))]
    c += [("ls", {"span": s}) for s in (4, 6, 8, 12)]
    c += [("ls", {"span": s, "hl": h}) for s, h in ((10, 3), (12, 4), (15, 5))]
    c += [("kalman", {"q": q, "r": r}) for q in (0.02, 0.05, 0.1, 0.2) for r in (2.0, 3.0, 5.0)]
    c += [("kalman", {"q": q, "r": r, "adapt": 10.0}) for q, r in ((0.05, 3.0), (0.1, 3.0), (0.1, 5.0))]
    return c


def cand_name(kind, params):
    return kind + (" " + " ".join(f"{k}={v}" for k, v in params.items()) if params else "")


# ---------------------------------------------------------------------------------------------
# Oracle (kijkt naar voren en achteren) en gaten
# ---------------------------------------------------------------------------------------------
def oracle(t, g, half_window_min=6.0, sigma_min=2.0):
    n = len(t)
    lvl = np.full(n, np.nan)
    sl = np.full(n, np.nan)
    for i in range(n):
        j0 = np.searchsorted(t, t[i] - half_window_min * 60, side="left")
        j1 = np.searchsorted(t, t[i] + half_window_min * 60, side="right")
        x = (t[j0:j1] - t[i]) / 60.0
        if len(x) < 6 or x.min() > -3 or x.max() < 3:
            continue
        w = np.exp(-0.5 * (x / sigma_min) ** 2)
        y = g[j0:j1]
        sw, swx, swy = w.sum(), (w * x).sum(), (w * y).sum()
        swxx, swxy = (w * x * x).sum(), (w * x * y).sum()
        den = sw * swxx - swx * swx
        if den <= 1e-9:
            continue
        slope = (sw * swxy - swx * swy) / den
        sl[i] = slope
        lvl[i] = (swy - slope * swx) / sw
    return lvl, sl


def near_gap_mask(t, minutes=6.0):
    n = len(t)
    bad = np.zeros(n, bool)
    big = np.where(np.diff(t) > 150)[0]
    for k in big:
        lo, hi = t[k] - minutes * 60, t[k + 1] + minutes * 60
        bad |= (t >= lo) & (t <= hi)
    return bad


def interp_valid(t_src, y_src, tq):
    ok = np.isfinite(y_src)
    if ok.sum() < 2:
        return np.full(len(tq), np.nan)
    out = np.interp(tq, t_src[ok], y_src[ok])
    out[(tq < t_src[ok][0]) | (tq > t_src[ok][-1])] = np.nan
    return out


# ---------------------------------------------------------------------------------------------
# Gebeurtenissen uit de oracle: stijgingen en pieken
# ---------------------------------------------------------------------------------------------
def find_rises(t, lo, so, thr, rearm=0.3, min_gain=20.0, quiet_min=10.0):
    events = []
    armed = True
    last_high = -1e18
    prev = np.nan
    for i in range(len(t)):
        s = so[i]
        if not np.isfinite(s):
            prev = np.nan
            continue
        if s >= rearm:
            last_high = t[i]
        if not armed and t[i] - last_high >= quiet_min * 60:
            armed = True
        if armed and s >= thr and np.isfinite(prev) and prev < thr:
            frac = (thr - prev) / (s - prev) if s != prev else 1.0
            t0 = t[i - 1] + frac * (t[i] - t[i - 1])
            l0 = np.interp(t0, t, np.nan_to_num(lo, nan=0.0))
            l20 = interp_valid(t, lo, np.array([t0 + 20 * 60]))[0]
            if np.isfinite(l20) and l20 - l0 >= min_gain:
                events.append(t0)
                armed = False
        prev = s
    return events


def find_peaks(t, lo, so, min_rise=30.0):
    peaks = []
    n = len(t)
    for i in range(n):
        if not np.isfinite(lo[i]):
            continue
        a = np.searchsorted(t, t[i] - 12 * 60, side="left")
        b = np.searchsorted(t, t[i] + 12 * 60, side="right")
        win = lo[a:b]
        if not np.isfinite(win).all() or lo[i] < np.nanmax(win) - 1e-9:
            continue
        a2 = np.searchsorted(t, t[i] - 45 * 60, side="left")
        prior = lo[a2:i + 1]
        if not np.isfinite(prior).any() or lo[i] - np.nanmin(prior) < min_rise:
            continue
        ss = so[a2:i + 1]
        if not np.isfinite(ss).any() or np.nanmax(ss) < 0.8:
            continue
        k = a2 + int(np.nanargmax(ss))
        peaks.append((t[i], t[k], float(so[k])))
    merged = []
    for p in peaks:
        if merged and p[0] - merged[-1][0] < 30 * 60:
            continue  # zelfde piek (de oracle-piek is al het hoogste punt in +-12 min)
        merged.append(p)
    return merged


def oracle_flat_time(t, so, tmax, smax, flat):
    i0 = np.searchsorted(t, tmax, side="left")
    for i in range(i0, len(t)):
        if np.isfinite(so[i]) and so[i] <= flat * smax:
            return t[i]
    return np.nan


# ---------------------------------------------------------------------------------------------
# Detectie op een reeks (tijd, helling). Dezelfde regels voor 1-minuut en voor het 5-minuut raster.
# ---------------------------------------------------------------------------------------------
def detect_rise(ts, ss, t0, thr, confirm, before=10, after=25):
    lo, hi = t0 - before * 60, t0 + after * 60
    run = 0
    for tt, s in zip(ts, ss):
        if tt < lo:
            continue
        if tt > hi:
            break
        if np.isfinite(s) and s >= thr:
            run += 1
            if run >= confirm:
                return (tt - t0) / 60.0
        else:
            run = 0
    return np.nan


def detect_flat(ts, ss, peak, flat, confirm, rise_thr):
    tp, tmax, smax = peak
    lo, hi = tmax - 10 * 60, tp + 15 * 60
    run = 0
    runmax = -1e9
    for tt, s in zip(ts, ss):
        if tt < lo:
            continue
        if tt > hi:
            break
        if not np.isfinite(s):
            run = 0
            continue
        runmax = max(runmax, s)
        if runmax >= 0.6 * smax and runmax >= rise_thr * 0.8 and s <= flat * runmax:
            run += 1
            if run >= confirm:
                return (tp - tt) / 60.0  # positief = vóór de piek
        else:
            run = 0
    return np.nan


def false_alarms(ts, ss, to, so, thr, confirm, hours):
    """Aantal episodes met helling >= thr terwijl de oracle in +-8 min geen echte stijging zag."""
    episodes = 0
    run = 0
    for tt, s in zip(ts, ss):
        a = np.searchsorted(to, tt - 8 * 60, side="left")
        b = np.searchsorted(to, tt + 8 * 60, side="right")
        seg = so[a:b]
        if not np.isfinite(seg).any():
            run = 0
            continue
        real = np.nanmax(seg) >= 0.5
        if np.isfinite(s) and s >= thr and not real:
            run += 1
            if run == confirm:
                episodes += 1
        else:
            run = 0
    return episodes / max(hours / 24.0, 1e-9)


def make_grid(t, level, slope, phase_min, lead, max_lead):
    """Wat AAPS zou krijgen: 1 waarde per 5 minuten, op het laatste filterresultaat (max 90 s oud)."""
    tk = np.arange(t[0] + phase_min * 60, t[-1], 300.0)
    j = np.searchsorted(t, tk, side="right") - 1
    ok = (j >= 0) & (tk - t[np.clip(j, 0, None)] <= 90)
    val = np.full(len(tk), np.nan)
    jj = j[ok]
    sl = np.nan_to_num(slope[jj], nan=0.0)
    val[ok] = level[jj] + np.clip(lead * sl, -max_lead, max_lead)
    return tk, val


def grid_slope(tk, val):
    sl = np.full(len(tk), np.nan)
    for k in range(1, len(tk)):
        if np.isfinite(val[k]) and np.isfinite(val[k - 1]) and abs(tk[k] - tk[k - 1] - 300) < 120:
            sl[k] = (val[k] - val[k - 1]) / ((tk[k] - tk[k - 1]) / 60.0)
    return sl


# ---------------------------------------------------------------------------------------------
# Meten
# ---------------------------------------------------------------------------------------------
def lag_of(t, level, lo, valid, shifts):
    best = (np.nan, np.nan)
    rmse0 = np.nan
    for s in shifts:
        ref = interp_valid(t, lo, t - s * 60)
        m = valid & np.isfinite(ref) & np.isfinite(level)
        if m.sum() < 20:
            continue
        r = float(np.sqrt(np.mean((level[m] - ref[m]) ** 2)))
        if abs(s) < 1e-9:
            rmse0 = r
        if not np.isfinite(best[1]) or r < best[1]:
            best = (s, r)
    return best[0], best[1], rmse0


def noise_of(t, level):
    d = np.diff(t)
    ok = (d[:-1] > 50) & (d[:-1] < 70) & (d[1:] > 50) & (d[1:] < 70)
    d2 = level[2:] - 2 * level[1:-1] + level[:-2]
    d2 = d2[ok & np.isfinite(d2)]
    return float(np.sqrt(np.mean(d2 ** 2))) if len(d2) else np.nan


def evaluate(t, g, args):
    lo, so = oracle(t, g)
    gap = near_gap_mask(t)
    valid = np.isfinite(lo) & ~gap
    hours = valid.sum() / 60.0
    rises = find_rises(t, lo, so, args.rise_thr)
    peaks = find_peaks(t, lo, so)
    flat_o = [oracle_flat_time(t, so, p[1], p[2], args.flat) for p in peaks]
    info = {"lo": lo, "so": so, "valid": valid, "hours": hours, "rises": rises, "peaks": peaks, "flat_o": flat_o}

    rows = []
    results = {}
    for kind, params in candidates():
        fn = FILTERS[kind]
        level, slope = fn(t, g, **params)
        base_noise = None
        for lead in ([0.0] if kind == "raw" else args.leads):
            name = cand_name(kind, params) + (f" lead={lead:g}" if lead else "")
            sent_level = level.copy()
            if lead:
                sent_level = level + np.clip(lead * np.nan_to_num(slope, nan=0.0), -args.max_lead, args.max_lead)
            lag, rmse_best, rmse0 = lag_of(t, sent_level, lo, valid, np.arange(-6, 12.01, 0.5))
            row = {"filter": name, "ruis": noise_of(t, sent_level), "lag_min": lag, "vorm_rmse": rmse_best, "rmse_0": rmse0}

            # overshoot: hoeveel de zending boven de oracle uitkomt rond een piek (gevaar van extrapolatie)
            over = []
            for pk in peaks:
                m = (t >= pk[0] - 10 * 60) & (t <= pk[0] + 10 * 60) & np.isfinite(lo)
                if m.any():
                    over.append(float(np.max(sent_level[m] - lo[m])))
            row["overshoot"] = float(np.mean(over)) if over else np.nan

            # 1-minuut detectie op de eigen helling van het filter
            d1 = [detect_rise(t, slope, t0, args.rise_thr, args.confirm) for t0 in rises]
            row["stijg_1min"] = np.nanmean(d1) if np.isfinite(d1).any() else np.nan
            row["gemist_1min"] = int(np.sum(~np.isfinite(d1)))
            f1 = [detect_flat(t, slope, p, args.flat, args.confirm, args.rise_thr) for p in peaks]
            row["piek_1min"] = np.nanmean(f1) if np.isfinite(f1).any() else np.nan
            row["fa_dag_1min"] = false_alarms(t, slope, t, so, args.rise_thr, args.confirm, hours)

            # AAPS-zicht: 5-minuut raster, alle 5 fasen
            d5, miss5, f5, fa5 = [], 0, [], []
            for phase in range(5):
                tk, val = make_grid(t, level, slope, phase, lead, args.max_lead)
                gs = grid_slope(tk, val)
                for t0 in rises:
                    d = detect_rise(tk, gs, t0, args.rise_thr, 1)
                    d5.append(d)
                for p in peaks:
                    f5.append(detect_flat(tk, gs, p, args.flat, 1, args.rise_thr))
                fa5.append(false_alarms(tk, gs, t, so, args.rise_thr, 1, hours))
            d5 = np.array(d5, float)
            row["stijg_5min"] = np.nanmean(d5) if np.isfinite(d5).any() else np.nan
            row["gemist_5min"] = float(np.sum(~np.isfinite(d5))) / 5.0
            row["piek_5min"] = np.nanmean(f5) if np.isfinite(f5).any() else np.nan
            row["fa_dag_5min"] = float(np.mean(fa5))
            rows.append(row)
            results[name] = (sent_level, slope)
    return pd.DataFrame(rows), results, info


def evaluate_reference(ref, t, lo, so, valid, info, args):
    rows = []
    for sensor, grp in ref.items():
        tr = grp["t"].to_numpy()
        for col in ("gefilterd_mgdl", "gekalibreerd_mgdl", "ruw_mgdl"):
            v = grp[col].to_numpy(dtype=float)
            # lag t.o.v. de oracle (gemiddelde offset eruit)
            best = (np.nan, np.nan)
            for s in np.arange(0, 30.01, 1.0):
                o = interp_valid(t, lo, tr - s * 60)
                m = np.isfinite(o) & np.isfinite(v)
                if m.sum() < 8:
                    continue
                d = v[m] - o[m]
                r = float(np.sqrt(np.mean((d - d.mean()) ** 2)))
                if not np.isfinite(best[1]) or r < best[1]:
                    best = (s, r)
            gs = grid_slope(tr, v)
            d5 = [detect_rise(tr, gs, t0, args.rise_thr, 1) for t0 in info["rises"]]
            f5 = [detect_flat(tr, gs, p, args.flat, 1, args.rise_thr) for p in info["peaks"]]
            rows.append({"referentie": f"{sensor} {col}", "lag_min": best[0], "vorm_rmse": best[1],
                         "stijg_5min": np.nanmean(d5) if np.isfinite(d5).any() else np.nan,
                         "gemist_5min": int(np.sum(~np.isfinite(d5))),
                         "piek_5min": np.nanmean(f5) if np.isfinite(f5).any() else np.nan,
                         "fa_dag_5min": false_alarms(tr, gs, t, so, args.rise_thr, 1, info["hours"])})
    return pd.DataFrame(rows)


def evaluate_fingersticks(fs, t, g, results, ref, ranked_names, args):
    rows = []
    for _, r in fs.iterrows():
        row = {"tijd": (EPOCH + pd.Timedelta(seconds=r["t"])).strftime("%d-%m %H:%M"), "vinger": round(r["mgdl"])}
        j = np.searchsorted(t, r["t"], side="right") - 1
        if j >= 0 and r["t"] - t[j] <= 150:
            row["libre_raw"] = round(g[j])
            for name in ranked_names:
                row[name] = round(results[name][0][j])
        for sensor, grp in ref.items():
            tr = grp["t"].to_numpy()
            k = np.searchsorted(tr, r["t"], side="right") - 1
            if k >= 0 and r["t"] - tr[k] <= 360:
                row[sensor] = round(float(grp["gekalibreerd_mgdl"].iloc[k]))
        rows.append(row)
    df = pd.DataFrame(rows)
    stats = []
    for col in [c for c in df.columns if c not in ("tijd", "vinger")]:
        m = df[col].notna()
        if m.sum():
            e = df.loc[m, col] - df.loc[m, "vinger"]
            stats.append({"bron": col, "n": int(m.sum()), "bias": round(float(e.mean()), 1),
                          "MARD_%": round(float((e.abs() / df.loc[m, "vinger"]).mean() * 100), 1)})
    return df, pd.DataFrame(stats)


# ---------------------------------------------------------------------------------------------
# Plaatjes
# ---------------------------------------------------------------------------------------------
def make_plots(out, t, g, results, info, ranked_names, ref, args):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        print("matplotlib niet gevonden: plaatjes overgeslagen (pip install matplotlib)")
        return

    def tx(sec):
        return pd.to_datetime(EPOCH + pd.to_timedelta(sec, unit="s"))

    events = [("stijging", t0, t0 - 40 * 60, t0 + 50 * 60) for t0 in info["rises"]]
    events += [("piek", p[0], p[0] - 60 * 60, p[0] + 30 * 60) for p in info["peaks"]]
    colors = ["tab:red", "tab:blue", "tab:green"]
    for n, (kind, tc, lo_t, hi_t) in enumerate(events[:12], 1):
        fig, ax = plt.subplots(figsize=(11, 5))
        m = (t >= lo_t) & (t <= hi_t)
        ax.plot(tx(t[m]), g[m], ".", color="lightgray", label="Libre ruw (1 min)")
        ax.plot(tx(t[m]), info["lo"][m], "-", color="black", lw=1, label="oracle (kijkt vooruit)")
        for c, name in zip(colors, ranked_names[:3]):
            lv = results[name][0]
            ax.plot(tx(t[m]), lv[m], "-", color=c, lw=1.4, label=name)
        if ranked_names:
            lv, sl = results[ranked_names[0]]
            tk, val = make_grid(t, lv, sl, 0, 0.0, args.max_lead)
            mk = (tk >= lo_t) & (tk <= hi_t)
            ax.plot(tx(tk[mk]), val[mk], "o", color="tab:red", ms=6, mfc="none", label="5-min zending (beste, fase 0)")
        for sensor, grp in ref.items():
            tr = grp["t"].to_numpy()
            mr = (tr >= lo_t) & (tr <= hi_t)
            ax.plot(tx(tr[mr]), grp["gefilterd_mgdl"].to_numpy()[mr], "s--", color="tab:orange", ms=5, label=f"{sensor} gefilterd (5 min)")
        ax.axvline(tx(np.array([tc]))[0], color="k", ls=":")
        ax.set_title(f"{kind} {n}: {tx(np.array([tc]))[0]:%d-%m %H:%M}")
        ax.set_ylabel("mg/dL")
        ax.legend(fontsize=7, loc="best")
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(os.path.join(out, f"{n:02d}_{kind}.png"), dpi=110)
        plt.close(fig)

    if ranked_names:
        fig, ax = plt.subplots(figsize=(13, 5))
        ax.plot(tx(t), g, ".", ms=2, color="lightgray", label="Libre ruw")
        ax.plot(tx(t), results[ranked_names[0]][0], "-", lw=1, color="tab:red", label=ranked_names[0])
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(os.path.join(out, "00_overzicht.png"), dpi=110)
        plt.close(fig)


# ---------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", help="map met libre3_minuut_*.csv en referentie_*.csv")
    ap.add_argument("--out", help="uitvoermap (standaard: <folder>/resultaat)")
    ap.add_argument("--demo", action="store_true", help="synthetische data")
    ap.add_argument("--rise-thr", type=float, default=1.0, help="helling (mg/dL per min) die 'stijging' heet [1.0]")
    ap.add_argument("--flat", type=float, default=0.4, help="afvlakking = helling onder deze fractie van de max [0.4]")
    ap.add_argument("--confirm", type=int, default=2, help="aantal opeenvolgende 1-min metingen voor een detectie [2]")
    ap.add_argument("--leads", default="0,2,4", help="extrapolatie in minuten, bv. 0,2,4 [0,2,4]")
    ap.add_argument("--max-lead", type=float, default=15.0, help="max extrapolatie in mg/dL [15]")
    ap.add_argument("--max-fa", type=float, default=3.0, help="max valse alarmen per dag voor de ranglijst [3]")
    args = ap.parse_args()
    args.leads = [float(x) for x in args.leads.split(",") if x.strip()]

    if args.demo:
        (t, g), ref, fs = make_demo()
        out = args.out or os.path.join(os.getcwd(), "resultaat_demo")
    else:
        if not args.folder:
            ap.error("geef een map op, of --demo")
        t, g = load_libre(args.folder)
        ref = load_reference(args.folder)
        fs = load_fingersticks(args.folder)
        out = args.out or os.path.join(args.folder, "resultaat")
    os.makedirs(out, exist_ok=True)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    pd.set_option("display.float_format", lambda x: f"{x:.2f}")

    span_h = (t[-1] - t[0]) / 3600.0
    print(f"Libre: {len(t)} metingen, {span_h:.1f} uur, {np.sum(np.diff(t) > 150)} gaten > 2,5 min")

    table, results, info = evaluate(t, g, args)
    print(f"Oracle zag {len(info['rises'])} stijging(en) en {len(info['peaks'])} piek(en) "
          f"(stijging = helling >= {args.rise_thr} mg/dL/min en +20 mg/dL in 20 min; piek = max na >= 30 mg/dL stijging)")
    if not info["rises"]:
        print("Nog geen stijgingen: de detectie-kolommen blijven leeg. Verzamel meer data (maaltijden).")

    ok = table[(table["fa_dag_5min"] <= args.max_fa)].copy()
    if info["rises"]:
        ok = ok.sort_values(["stijg_5min", "ruis"], na_position="last")
    else:
        ok = ok.sort_values(["vorm_rmse", "ruis"])
    ranked = ok["filter"].tolist()

    cols = ["filter", "ruis", "lag_min", "vorm_rmse", "overshoot", "stijg_1min", "stijg_5min", "gemist_5min",
            "piek_1min", "piek_5min", "fa_dag_1min", "fa_dag_5min"]
    print("\nRANGLIJST (valse alarmen 5-min <= %.1f/dag; gesorteerd op vroegste stijging in 5-min zicht)" % args.max_fa)
    print("  ruis = RMS 2e verschil (mg/dL, lager = gladder) | lag_min = vertraging t.o.v. oracle (negatief = voorloopt)")
    print("  overshoot = max. hoeveelheid (mg/dL) die de zending boven de oracle uitkomt rond een piek (lager = veiliger)")
    print("  stijg_* / piek_* in minuten: stijg = gemeten t.o.v. oracle-start (lager = eerder); piek = minuten VOOR de piek (hoger = eerder)")
    print(ok[cols].head(15).to_string(index=False))
    print("\nVOOR VERGELIJKING: raw")
    print(table[table["filter"] == "raw"][cols].to_string(index=False))

    table.to_csv(os.path.join(out, "resultaten_filters.csv"), sep=";", index=False)

    if ref:
        rt = evaluate_reference(ref, t, info["lo"], info["so"], info["valid"], info, args)
        print("\nREFERENTIESENSOREN (5 min) met dezelfde regels")
        print(rt.to_string(index=False))
        rt.to_csv(os.path.join(out, "resultaten_referentie.csv"), sep=";", index=False)

    if fs is not None and len(fs):
        names = ranked[:3]
        fdf, fstats = evaluate_fingersticks(fs, t, g, results, ref, names, args)
        print("\nVINGERPRIKKEN (mg/dL)  — let op: vingerprik loopt bij snelle verandering voor op sensoren")
        print(fdf.to_string(index=False))
        print(fstats.to_string(index=False))
        fdf.to_csv(os.path.join(out, "vingerprikken_vergelijking.csv"), sep=";", index=False)

    make_plots(out, t, g, results, info, ranked, ref, args)
    print(f"\nKlaar. Resultaten en plaatjes staan in: {out}")


if __name__ == "__main__":
    main()
