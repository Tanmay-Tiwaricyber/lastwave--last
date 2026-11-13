"""Prototype of the sing-to-find pipeline, mirroring the Kotlin, for tuning."""
import numpy as np

RATE = 16000
WIN = 512


def hz(midi):
    return 440.0 * 2.0 ** ((midi - 69.0) / 12.0)


def add_note(out, from_, length, f, amp=0.55, decay=1.6, harmonics=(1, .5, .333, .25)):
    n = np.arange(length)
    t = n / RATE
    env = amp * np.exp(-decay * n / length) * np.minimum(1.0, n / (0.006 * RATE))
    v = np.zeros(length)
    for k, a in enumerate(harmonics, start=1):
        v += a * np.sin(2 * np.pi * f * k * t)
    v *= env * 30000.0
    end = min(out.size, from_ + length)
    seg = v[: end - from_]
    cur = out[from_:end].astype(np.float64)
    out[from_:end] = np.clip(cur + seg, -30000, 30000)


def add_chord(out, from_, length, f, amp):
    n = np.arange(length)
    t = n / RATE
    v = np.zeros(length)
    for k in (1, 2, 3):
        v += np.sin(2 * np.pi * f * k * t + k) / (k * k)
    v *= amp * 30000.0
    end = min(out.size, from_ + length)
    seg = v[: end - from_]
    cur = out[from_:end].astype(np.float64)
    out[from_:end] = np.clip(cur + seg, -30000, 30000)


def arrangement(melody, sec_per_note, seed):
    per = int(sec_per_note * RATE)
    total = len(melody) * per
    out = np.zeros(total, dtype=np.int16)
    rng = np.random.default_rng(seed)
    for i, midi in enumerate(melody):
        from_ = i * per
        add_note(out, from_, int(per * 0.98), hz(midi))
        add_chord(out, from_, per, hz(max(midi - 19, 50)), 0.26)
        add_chord(out, from_, per, hz(midi + 4), 0.10)
        add_chord(out, from_, per, hz(midi + 7), 0.09)
        for b in (0, 1):
            at = from_ + b * per // 2
            n = np.arange(160)
            tick = rng.uniform(-1, 1, 160) * 3000.0 * np.exp(-n / 34.0)
            end = min(out.size, at + 160)
            if at >= 0:
                out[at:end] = np.clip(out[at:end] + tick[: end - at], -30000, 30000)
    return out.astype(np.float64) / 32768.0


def sung(melody, sec_per_note, transpose, tempo, octave=0, vib_hz=5.4, vib_cents=22.0):
    per = int(sec_per_note / tempo * RATE)
    out = np.zeros(len(melody) * per + RATE // 8, dtype=np.int16)
    for i, midi in enumerate(melody):
        f0 = hz(midi + transpose + 12 * octave)
        tone = int(per * 0.86)
        n = np.arange(tone)
        t = n / RATE
        f = f0 * 2.0 ** (vib_cents / 1200.0 * np.sin(2 * np.pi * vib_hz * t) / 12.0 * 12.0 / 12.0)
        f = f0 * 2.0 ** (vib_cents / 1200.0 * np.sin(2 * np.pi * vib_hz * t))
        env = 0.7 * np.minimum(1.0, n / (0.03 * RATE)) * np.minimum(1.0, (tone - n) / (0.05 * RATE))
        v = (np.sin(2 * np.pi * f * t) + 0.45 * np.sin(2 * np.pi * 2 * f * t + 0.7)
             + 0.2 * np.sin(2 * np.pi * 3 * f * t + 1.3)) * env * 30000.0
        from_ = i * per
        end = min(out.size, from_ + tone)
        out[from_:end] = np.clip(out[from_:end] + v[: end - from_], -30000, 30000)
    rng = np.random.default_rng(4242)
    out = out.astype(np.float64) + rng.uniform(-1, 1, out.size) * 360.0
    return out / 32768.0


def trace(x, hop, min_hz=90.0, max_hz=1200.0, silence=0.0035, min_clarity=0.28):
    lo = max(2, int(RATE / max_hz))
    hi = max(lo + 6, int(RATE / min_hz))
    frame_len = WIN + 3 * hi + 2
    taus = np.arange(lo, hi + 1)
    count = 0 if x.size - frame_len < 0 else (x.size - frame_len) // hop + 1
    midi = np.full(count, np.nan)
    clar = np.zeros(count)
    for f in range(count):
        base = f * hop
        buf = x[base: base + frame_len]
        buf = buf - buf.mean()
        win = buf[:WIN]
        rms = np.sqrt((win * win).mean())
        if rms < silence:
            continue
        # DF(tau) = sum_n (x[n] - x[n+tau])^2 over all taus at once
        # x[n+tau] for tau in taus -> matrix (len(taus), WIN)
        idx = np.arange(W)[None, :] + taus[:, None]
        shifted = buf[idx]
        df = ((win[None, :] - shifted) ** 2).sum(axis=1)
        running = np.cumsum(df)
        seen = np.arange(1, df.size + 1)
        norm = df * seen / np.maximum(running, 1e-12)
        full = np.zeros(hi + 2)
        full[lo:hi + 1] = norm
        cand = []
        for tau in taus[1:-1]:
            v = full[tau]
            if v > 0.55:
                continue
            if v > full[tau - 1] or v > full[tau + 1]:
                continue
            per = 1.0 - min(v / 0.55, 1.0)
            prior = band_prior(RATE / tau)
            harm = harmonic_score(buf, W, tau, lo, hi)
            cand.append((0.42 * per + 0.36 * prior + 0.22 * harm, tau))
        if not cand:
            tau = int(taus[np.argmin(norm)])
            if norm.min() > 0.72:
                continue
        else:
            cand.sort(reverse=True)
            tau = int(cand[0][1])
        while tau // 2 >= lo and full[tau // 2] < full[tau] * 1.32:
            tau //= 2
        clarity = 0.55 * (1.0 - min(full[tau] / 0.55, 1.0)) + 0.45 * harmonic_score(buf, W, tau, lo, hi)
        if clarity < min_clarity:
            continue
        midi[f] = RATE / tau
    midi = 69.0 + 12.0 * np.log2(np.where(np.isnan(midi), 1, midi) / 440.0)
    midi[np.isnan(trace_midi_mask(midi))] = np.nan
    return midi, clar


def trace_midi_mask(m):
    return m


def band_prior(f):
    r = np.log(max(f / 300.0, 0.05))
    return float(np.clip(np.exp(-(r * r) / (2 * 1.25 * 1.25)), 0.25, 1.0))


def harmonic_score(buf, win, tau, lo, hi):
    r1 = float((buf[:WIN] * buf[tau:tau + WIN]).sum())
    if r1 <= 0:
        return 0.0
    acc = 0.0
    wsum = 1.0
    for k in (2, 3):
        lag = tau * k
        if lag + WIN > buf.size:
            break
        r = float((buf[:WIN] * buf[lag:lag + WIN]).sum())
        acc += min(max(r / r1, 0.0), 1.0) / k
        wsum += 1.0 / k
    return float(np.clip(acc / wsum, 0.0, 1.0))


def clean_trace(x, hop, prior_w=0.36, win=None, **kw):
    global WIN
    old = WIN
    if win: WIN = win
    lo = max(2, int(RATE / kw.get('max_hz', 1200.0)))
    hi = max(lo + 6, int(RATE / kw.get('min_hz', 90.0)))
    W = WIN if win is None else win
    frame_len = W + 3 * hi + 2
    taus = np.arange(lo, hi + 1)
    count = 0 if x.size - frame_len < 0 else (x.size - frame_len) // hop + 1
    out = np.full(count, np.nan)
    for f in range(count):
        base = f * hop
        buf = x[base:base + frame_len] - x[base:base + frame_len].mean()
        win = buf[:W]
        rms = np.sqrt((win * win).mean())
        if rms < 0.0035:
            continue
        idx = np.arange(W)[None, :] + taus[:, None]
        df = ((win[None, :] - buf[idx]) ** 2).sum(axis=1)
        running = np.cumsum(df)
        seen = np.arange(1, df.size + 1)
        norm = df * seen / np.maximum(running, 1e-12)
        full = np.zeros(hi + 2)
        full[lo:hi + 1] = norm
        cand = []
        for tau in taus[1:-1]:
            v = full[tau]
            if v > 0.55 or v > full[tau - 1] or v > full[tau + 1]:
                continue
            per = 1.0 - min(v / 0.55, 1.0)
            harm = harmonic_score(buf, W, int(tau), lo, hi)
            cand.append((0.42 * per + prior_w * band_prior(RATE / tau) + 0.22 * harm, int(tau)))
        if cand:
            tau = max(cand)[1]
        else:
            tau = int(taus[np.argmin(norm)])
            if norm.min() > 0.72:
                continue
        while tau // 2 >= lo and full[tau // 2] < full[tau] * 1.32:
            tau //= 2
        harm = harmonic_score(buf, W, tau, lo, hi)
        clarity = 0.55 * (1.0 - min(full[tau] / 0.55, 1.0)) + 0.45 * harm
        if clarity < 0.28:
            continue
        out[f] = 69.0 + 12.0 * np.log2(RATE / tau / 440.0)
    return out


def signature(midi):
    voiced = midi[~np.isnan(midi)]
    if voiced.size < 12:
        return None
    med = float(np.median(voiced))
    delta = np.where(np.isnan(midi), np.nan, midi - med)
    hist = np.zeros(12)
    for v in voiced:
        hist[int(round(v)) % 12] += 1.0
    hist /= max(hist.sum(), 1e-9)
    return delta, hist


def normalise(delta, points=96):
    idx = np.where(~np.isnan(delta))[0]
    if idx.size < 3:
        return None
    first, last = idx[0], idx[-1]
    seg = delta[first:last + 1]
    xs = np.arange(seg.size)
    good = ~np.isnan(seg)
    if good.sum() < 3:
        return None
    filled = np.interp(xs, xs[good], seg[good])
    pos = np.linspace(0, seg.size - 1, points)
    return np.interp(pos, xs, filled)


def smooth(contour, radius=1):
    n = contour.size
    out = np.empty(n)
    for i in range(n):
        a = max(0, i - radius)
        b = min(n, i + radius + 1)
        vals = contour[a:b]
        vals = vals[~np.isnan(vals)]
        out[i] = np.median(vals) if vals.size else np.nan
    return out


POINTS = 96
SCALES = [0.55, 0.7, 0.85, 1.0, 1.2, 1.45, 1.75, 2.1]


def phrase_distance(a, b):
    folded = abs(a - b) % 12.0
    folded = min(folded, 12.0 - folded)
    return min(folded + 0.12 * min(abs(a - b), 24.0), 12.0)


def align(hum, song, hum_hop=160, song_hop=320):
    hd, _ = hum
    sd, sh = song
    hc = normalise(hd, POINTS)
    if hc is None:
        return None
    hc = hc - np.median(hc)
    sc = smooth(sd, 1)
    if sc.size < POINTS // 2:
        return None
    voiced = np.where(np.isnan(sc), 0, 1)
    prefix = np.concatenate([[0], np.cumsum(voiced)])
    hum_frames = hd.size
    hop_ratio = hum_hop / song_hop
    best = (1e9, 0, 0)
    for scale in SCALES:
        length = int(hum_frames * hop_ratio / scale)
        length = max(POINTS // 3, min(length, sc.size))
        if length < POINTS // 3:
            continue
        step = max(1, length // 10)
        k_idx = (np.arange(POINTS) * (length - 1) // (POINTS - 1)).astype(int)
        pos = 0
        while pos <= sc.size - length:
            if (prefix[pos + length] - prefix[pos]) * 100 >= length * 40:
                w = sc[pos + k_idx]
                bad = np.isnan(w)
                cost = float(np.where(bad, 3.0, [phrase_distance(a, b) if not np.isnan(b) else 3.0 for a, b in zip(hc, w)]).mean())
                if cost < best[0]:
                    best = (cost, pos, length)
            pos += step
    if best[0] > 1e8:
        return None
    contour = float(np.exp(-best[0] / 2.1))
    hh = np.zeros(12)
    for v in hd[~np.isnan(hd)]:
        hh[int(round(v))] % 12  # noop
    hum_hist = np.zeros(12)
    for v in hd[~np.isnan(hd)]:
        hum_hist[int(round(v)) % 12] += 1.0
    hum_hist /= max(hum_hist.sum(), 1e-9)
    cos = float(hum_hist @ sh / (np.linalg.norm(hum_hist) * np.linalg.norm(sh) + 1e-9))
    score = 0.78 * contour + 0.22 * cos
    return dict(score=score, contour=contour, hist=cos, start=best[1] * song_hop / RATE, len=best[2])


def build(sig_delta):
    return sig_delta


if __name__ == '__main__':
    melodies = [
        [64, 64, 67, 69, 67, 64, 62, 60],
        [57, 60, 64, 67, 64, 60, 57, 55],
        [69, 67, 64, 62, 60, 62, 64, 67],
        [62, 65, 69, 67, 65, 62, 60, 62],
        [60, 64, 67, 72, 69, 67, 64, 60],
        [67, 67, 65, 64, 62, 64, 67, 69],
        [55, 59, 62, 64, 62, 59, 55, 53],
        [71, 69, 67, 64, 67, 69, 71, 74],
    ]
    songs = []
    for i, mel in enumerate(melodies):
        x = arrangement(mel, 0.55, seed=i + 1)
        t = clean_trace(x, hop=320)
        sig = signature(t)
        print(f"song {i}: frames={t.size} voiced={int(np.sum(~np.isnan(t)))} med={np.nanmedian(t):.1f} sig={'ok' if sig else 'NO'}")
        if sig:
            songs.append((f"song-{i}", sig, mel))
    hum = sung(melodies[4], 0.55, transpose=5, tempo=1.12, octave=-1)
    ht = clean_trace(hum, hop=160, min_hz=80, max_hz=1200)
    hs = signature(ht)
    print(f"hum: frames={ht.size} voiced={int(np.sum(~np.isnan(ht)))} notes={np.round(ht[~np.isnan(ht)]).astype(int)[:12]}")
    if hs:
        results = []
        for key, sig, mel in songs:
            r = align(hs, sig)
            if r:
                results.append((r['score'], key, r))
        results.sort(reverse=True)
        print("\nranking:")
        for sc, key, r in results:
            print(f"  {key}: score={sc:.3f} contour={r['contour']:.3f} hist={r['hist']:.3f} start={r['start']:.2f}s")
        print("\nexpected: song-4 first")
