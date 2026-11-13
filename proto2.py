"""Second prototype: harmonic salience over an FFT, with optional spectral flux."""
import numpy as np

RATE = 16000
NFFT = 2048


def hz(m):
    return 440.0 * 2.0 ** ((m - 69.0) / 12.0)


def add(out, from_, v):
    end = min(out.size, from_ + v.size)
    if end <= from_:
        return
    out[from_:end] = np.clip(out[from_:end].astype(np.float64) + v[: end - from_], -30000, 30000)


def note(out, from_, length, f, amp=0.55, decay=1.6, harm=(1, .5, .333, .25)):
    n = np.arange(length)
    t = n / RATE
    env = amp * np.exp(-decay * n / length) * np.minimum(1.0, n / (0.006 * RATE))
    v = sum(a * np.sin(2 * np.pi * f * k * t) for k, a in enumerate(harm, start=1)) * env * 30000.0
    add(out, from_, v)


def pad(out, from_, length, f, amp):
    n = np.arange(length)
    t = n / RATE
    v = sum(np.sin(2 * np.pi * f * k * t + k) / (k * k) for k in (1, 2, 3)) * amp * 30000.0
    add(out, from_, v)


def arrangement(melody, sec_per_note=0.55, seed=1, bass_amp=0.26, pad_amp=0.10, hum=False):
    per = int(sec_per_note * RATE)
    out = np.zeros(len(melody) * per, dtype=np.int16)
    rng = np.random.default_rng(seed)
    for i, midi in enumerate(melody):
        from_ = i * per
        note(out, from_, int(per * 0.98), hz(midi))
        pad(out, from_, per, hz(max(midi - 19, 45)), bass_amp)
        pad(out, from_, per, hz(midi + 4), pad_amp)
        pad(out, from_, per, hz(midi + 7), pad_amp * 0.9)
        n = np.arange(160)
        add(out, from_, rng.uniform(-1, 1, 160) * 2600.0 * np.exp(-n / 34.0))
        add(out, from_ + per // 2, rng.uniform(-1, 1, 160) * 2600.0 * np.exp(-n / 34.0))
    return out.astype(np.float64) / 32768.0


def sung(melody, sec_per_note=0.55, transpose=0, tempo=1.0, octave=0, vib_hz=5.4, vib_cents=22.0):
    per = int(sec_per_note / tempo * RATE)
    out = np.zeros(len(melody) * per + RATE // 8, dtype=np.int16)
    for i, midi in enumerate(melody):
        f0 = hz(midi + transpose + 12 * octave)
        tone = int(per * 0.86)
        n = np.arange(tone)
        t = n / RATE
        f = f0 * 2.0 ** (vib_cents / 1200.0 * np.sin(2 * np.pi * vib_hz * t))
        env = 0.7 * np.minimum(1.0, n / (0.03 * RATE)) * np.minimum(1.0, (tone - n) / (0.05 * RATE))
        v = (np.sin(2 * np.pi * f * t) + 0.45 * np.sin(2 * np.pi * 2 * f * t + .7)
             + 0.22 * np.sin(2 * np.pi * 3 * f * t + 1.3) + 0.08 * np.sin(2 * np.pi * 4 * f * t)) * env * 12000.0
        add(out, i * per, v)
    rng = np.random.default_rng(4242)
    out = out.astype(np.float64) + rng.uniform(-1, 1, out.size) * 300.0
    return out / 32768.0


WINDOW = np.hanning(NFFT)
BIN_HZ = RATE / NFFT


def whiten(m, span=26):
    """Subtract a running spectral median, which removes the smeared energy of
    sustained pads and leaves the sharp peaks a melody makes."""
    pad = np.concatenate([m[:, :span], m, m[:, -span:]], axis=1)
    base = np.empty_like(m)
    for i in range(m.shape[1]):
        base[:, i] = np.median(pad[:, i:i + 2 * span + 1], axis=1)
    return np.maximum(0.0, m - base)


def magnitudes(x, hop, flux, whiten_spec=True):
    """Return the (possibly flux-weighted) magnitude per frame."""
    frames = (x.size - NFFT) // hop + 1
    spec = np.empty((frames, NFFT // 2 + 1))
    for i in range(frames):
        seg = x[i * hop: i * hop + NFFT] * WINDOW
        spec[i] = np.abs(np.fft.rfft(seg))
    if whiten_spec:
        spec = whiten(spec)
    if flux <= 0.0:
        return spec
    prev = np.concatenate([np.zeros((1, spec.shape[1])), spec[:-1]], axis=0)
    rise = np.maximum(0.0, spec - prev)
    # A steady accompaniment contributes nothing to `rise`; a changing vocal
    # line contributes a lot. Blend rather than replace so a held note in a
    # slow song does not disappear.
    return (1.0 - flux) * spec + flux * rise * 2.5


def trace2(x, hop=1024, fmin=110.0, fmax=1300.0, flux=0.0, min_clarity=0.30,
            prior_center=320.0, prior_width=1.35, sub_gain=1.55, gain=1.0, harmonics=6, whiten_spec=True):
    spec = magnitudes(x, hop, flux, whiten_spec)
    bins = np.arange(spec.shape[1])
    cents = np.arange(np.floor(np.log2(fmin / 20.0) * 1200), np.log2(fmax / 20.0) * 1200, gain)
    f0s = 20.0 * 2.0 ** (cents / 1200.0)
    # harmonic index tables
    idx = []
    weights = []
    for k in range(1, harmonics + 1):
        pos = k * f0s / BIN_HZ
        lo = np.floor(pos).astype(int)
        valid = (lo >= 0) & (lo < spec.shape[1] - 1)
        idx.append((lo, valid))
        weights.append(1.0 / (k ** 0.85))
    out_midi = np.full(spec.shape[0], np.nan)
    out_clar = np.zeros(spec.shape[0])
    log_prior = -((np.log(f0s / prior_center)) ** 2) / (2 * prior_width ** 2)
    prior = np.clip(np.exp(log_prior), 0.30, 1.0)
    for i in range(spec.shape[0]):
        m = spec[i]
        if np.sqrt((x[i * hop: i * hop + NFFT] ** 2).mean()) < 0.0035:
            continue
        peak = np.maximum.reduce([np.maximum(m[lo], m[lo + 1]) if lo.max() < m.size - 1 else m[np.clip(lo, 0, m.size - 1)]
                                  for lo, v in idx])
        sal = np.zeros(f0s.size)
        for (lo, v), w in zip(idx, weights):
            safe = np.clip(lo, 0, m.size - 2)
            val = np.maximum(m[safe], m[safe + 1])
            sal += w * val
        n_in_band = np.zeros(f0s.size)
        for k in range(1, harmonics + 1):
            n_in_band += (k * f0s < 3800.0)
        sal /= np.maximum(n_in_band, 1.0) * (np.sqrt((m[5:] ** 2).sum()) + 1e-6)
        score = sal * prior
        best = int(np.argmax(score))
        if best <= 0:
            continue
        # subharmonic (octave-down) check: only give up this octave if the
        # candidate an octave lower is decisively stronger.
        half = best
        while True:
            nxt = int(np.argmin(np.abs(f0s[:(half + 1)] - f0s[half] / 2.0))) if half > 2 else -1
            if nxt < 0 or nxt >= half:
                break
            if sal[nxt] > sal[half] * sub_gain:
                half = nxt
            else:
                break
        chosen = half
        clarity = float(min(1.0, sal[chosen] / (np.median(sal[sal > 0]) * 2.6 + 1e-9)))
        if chosen <= 0 or clarity < min_clarity:
            continue
        out_midi[i] = 69.0 + 12.0 * np.log2(f0s[chosen] / 440.0)
        out_clar[i] = min(clarity, 1.0)
    return out_midi, out_clar


def track_accuracy(midi, melody, sec_per_note=0.55, hop=1024):
    per_frames = sec_per_note * RATE / hop
    hits = total = 0
    for i, m in enumerate(melody):
        a = int(i * per_frames + 1)
        b = int((i + 1) * per_frames - 1)
        for f in range(a, max(a, b)):
            if f >= midi.size or np.isnan(midi[f]):
                continue
            total += 1
            if abs(midi[f] - m) < 1.0:
                hits += 1
    return hits, total


def signature(midi, clar=None):
    voiced = ~np.isnan(midi)
    if voiced.sum() < 12:
        return None
    med = np.median(midi[voiced])
    delta = np.where(voiced, midi - med, np.nan)
    hist = np.zeros(12)
    for v in midi[voiced]:
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


def smooth(c, radius=1):
    n = c.size
    out = np.empty(n)
    for i in range(n):
        vals = c[max(0, i - radius):min(n, i + radius + 1)]
        vals = vals[~np.isnan(vals)]
        out[i] = np.median(vals) if vals.size else np.nan
    return out


POINTS = 96
SCALES = [0.55, 0.7, 0.85, 1.0, 1.2, 1.45, 1.75, 2.1]


def pdist(a, b):
    folded = abs(a - b) % 12.0
    folded = min(folded, 12.0 - folded)
    return min(folded + 0.12 * min(abs(a - b), 24.0), 12.0)


def align(hum, song, hum_hop, song_hop, cost_scale=2.1, blend=(0.78, 0.22)):
    hd, hh_ = hum
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
    best = (1e9, 0, 0)
    for scale in SCALES:
        length = int(hd.size * (hum_hop / song_hop) / scale)
        length = max(POINTS // 3, min(length, sc.size))
        if length < POINTS // 3:
            continue
        step = max(1, length // 10)
        k_idx = (np.arange(POINTS) * (length - 1) // (POINTS - 1)).astype(int)
        pos = 0
        while pos + length <= sc.size:
            if (prefix[pos + length] - prefix[pos]) * 100 >= length * 40:
                w = sc[pos + k_idx]
                costs = [3.0 if np.isnan(x) else pdist(a, x) for a, x in zip(hc, w)]
                cost = float(np.mean(costs))
                if cost < best[0]:
                    best = (cost, pos, length)
            pos += step
    if best[0] > 1e8:
        return None
    contour = float(np.exp(-best[0] / cost_scale))
    cos = float(hh_ @ sh / (np.linalg.norm(hh_) * np.linalg.norm(sh) + 1e-9))
    score = blend[0] * contour + blend[1] * cos
    return dict(score=score, contour=contour, hist=cos, start=best[1] * song_hop / RATE)


MELODIES = [
    [64, 64, 67, 69, 67, 64, 62, 60],
    [57, 60, 64, 67, 64, 60, 57, 55],
    [69, 67, 64, 62, 60, 62, 64, 67],
    [62, 65, 69, 67, 65, 62, 60, 62],
    [60, 64, 67, 72, 69, 67, 64, 60],
    [67, 67, 65, 64, 62, 64, 67, 69],
    [55, 59, 62, 64, 62, 59, 55, 53],
    [71, 69, 67, 64, 67, 69, 71, 74],
]


def main():
    for wh in (False, True):
      for flux in (0.0, 0.35, 0.6):
        for hop in (1024, 512):
            acc = []
            for i, mel in enumerate(MELODIES):
                x = arrangement(mel, seed=i + 1)
                midi, _ = trace2(x, hop=hop, flux=flux, whiten_spec=wh)
                hits, total = track_accuracy(midi, mel, hop=hop)
                acc.append((hits, total))
            h = sum(a for a, _ in acc)
            t = sum(b for _, b in acc)
            print(f"whiten={wh} flux={flux} hop={hop}: melody frames right {h}/{t} = {h / max(t,1):.2f}")

    print("\n--- ranking, hum of song 4 (+5 semitones, octave down, 1.12x slower) ---")
    for wh, flux, hop, fmin in ((True, 0.35, 1024, 150.0), (True, 0.0, 1024, 150.0), (True, 0.35, 512, 150.0), (False, 0.0, 1024, 110.0)):
        songs = []
        for i, mel in enumerate(MELODIES):
            x = arrangement(mel, seed=i + 1)
            midi, _ = trace2(x, hop=hop, flux=flux, fmin=fmin, whiten_spec=wh)
            sig = signature(midi)
            if sig:
                songs.append((f"song-{i}", sig))
        hm, _ = trace2(sung(MELODIES[4], transpose=5, tempo=1.12, octave=-1), hop=256, flux=0.0,
                       fmin=90.0, prior_center=300.0, whiten_spec=False)
        hs = signature(hm)
        res = sorted(((align(hs, s, 256, hop)['score'], k) for k, s in songs), reverse=True)
        ok = res[0][1] == 'song-4'
        print(f"whiten={wh} flux={flux} hop={hop}: {'OK ' if ok else 'MISS'} " + " ".join(f"{k}:{s:.3f}" for s, k in res[:4]))


if __name__ == '__main__':
    main()
