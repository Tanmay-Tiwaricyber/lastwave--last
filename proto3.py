"""Cost matrix diagnostics: is the right song the best-aligned one at all?"""
import numpy as np, proto2 as P

def contour_of(sig):
    d = P.normalise(sig[0], P.POINTS)
    if d is None: return None
    return d - np.median(d)

def best_cost(hc, sc):
    best = 1e9
    for scale in P.SCALES:
        for length in (sc.size,):
            if length < 8: continue
            step = max(1, length // 20)
            k = (np.arange(P.POINTS) * (length - 1) // (P.POINTS - 1)).astype(int)
            pos = 0
            while pos + length <= sc.size:
                w = sc[pos + k]
                cs = [3.0 if np.isnan(x) else P.pdist(a, x) for a, x in zip(hc, w)]
                best = min(best, float(np.mean(cs)))
                pos += step
    return best

sigs_s, sigs_h = {}, {}
for i, mel in enumerate(P.MELODIES):
    x = P.arrangement(mel, seed=i + 1)
    m, _ = P.trace2(x, hop=1024, flux=0.0, fmin=110.0, whiten_spec=True)
    sigs_s[i] = P.signature(m)
    for tempo, tr, oct_ in ((1.0,0,0),(1.12,5,-1),(0.9,-7,1)):
        hm, _ = P.trace2(P.sung(mel, transpose=tr, tempo=tempo, octave=oct_), hop=256, flux=0.0, fmin=90.0, whiten_spec=False)
        sigs_h[(i,tempo,tr,oct_)] = P.signature(hm)

print("rows = hum of song i, cols = cost against each song (lower is better)")
for key, hs in sigs_h.items():
    i = key[0]
    if hs is None: print(key,'no hum sig'); continue
    hc = contour_of(hs)
    row = []
    for j in range(8):
        if sigs_s[j] is None: row.append(99); continue
        sc = P.smooth(sigs_s[j][0], 1); sc = np.where(np.isnan(sc), np.nan, sc - np.nanmedian(sc))
        row.append(best_cost(hc, sc))
    order = np.argsort(row)
    print(f"hum {str(key):26s} " + " ".join(f"{v:5.2f}" for v in row) + f"   best=song{order[0]} {'OK' if order[0]==i else 'MISS'}")
