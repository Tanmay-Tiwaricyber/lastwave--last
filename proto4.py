"""Where is the failure: the contour matcher, or the melody extraction?"""
import numpy as np, proto2 as P

def sig_from_melody(mel, frames):
    """The ideal contour: the notes themselves, resampled like a tracked trace."""
    midi = np.repeat(np.asarray(mel, dtype=float), frames)
    return P.signature(midi)

def contour(sig, points=P.POINTS):
    d = P.normalise(sig[0], points)
    return None if d is None else d - np.median(d)

def cost(hc, sc, scales=P.SCALES):
    best = 1e9
    for length in (sc.size,):
        if length < 8: continue
        k = (np.arange(len(hc)) * (length - 1) // (len(hc) - 1)).astype(int)
        w = sc[k]
        best = min(best, float(np.mean([3.0 if np.isnan(x) else P.pdist(a, x) for a, x in zip(hc, w)])))
    return best

hop_s, hop_h = 1024, 256
tracked, ideal = {}, {}
for i, mel in enumerate(P.MELODIES):
    x = P.arrangement(mel, seed=i + 1)
    m, _ = P.trace2(x, hop=hop_s, flux=0.0, fmin=110.0, whiten_spec=True)
    tracked[i] = P.signature(m)
    frames = m.size
    ideal[i] = sig_from_melody(mel, frames)

for name, bank in (("tracked", tracked), ("ideal", ideal)):
    print(f"\n== songs represented by {name} contours ==")
    for tr, tempo, oct_ in ((0, 1.0, 0), (5, 1.12, -1), (-7, 0.9, 1)):
        row = []
        for i, mel in enumerate(P.MELODIES):
            hm, _ = P.trace2(P.sung(mel, transpose=tr, tempo=tempo, octave=oct_), hop=hop_h,
                             flux=0.0, fmin=90.0, whiten_spec=False)
            hs = P.signature(hm)
            if hs is None: row.append(99); continue
            hc = contour(hs)
            row.append(min(cost(hc, contour(bank[j])) for j in (0,)) if False else cost(hc, contour(bank[i])))
        # full matrix row for this hum
        costs = []
        hm, _ = P.trace2(P.sung(mel if False else P.MELODIES[4], transpose=tr, tempo=tempo, octave=oct_),
                         hop=hop_h, flux=0.0, fmin=90.0, whiten_spec=False)
        hs = P.signature(hm)
        hc = contour(hs)
        for j in range(8):
            costs.append(cost(hc, contour(bank[j])) if bank[j] and contour(bank[j]) is not None else 99)
        order = int(np.argmin(costs))
        print(f" hum of song 4 (tr{tr:+d} t{tempo} o{oct_:+d}): " + " ".join(f"{v:5.2f}" for v in costs) +
              f"  best=song{order} {'OK' if order==4 else 'MISS'}")

# how good is the hummed contour alone?
hm, _ = P.trace2(P.sung(P.MELODIES[4], transpose=5, tempo=1.12, octave=-1), hop=hop_h, flux=0.0, fmin=90.0, whiten_spec=False)
print("\nhum tracked midi:", np.round(hm[~np.isnan(hm)]).astype(int)[:40])
print("true notes      :", [m + 5 - 12 for m in P.MELODIES[4]])
