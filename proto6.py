"""YIN for the sung line, spectral salience + Viterbi for the mix, matched note to note."""
import numpy as np
import proto_melody as Y      # YIN helpers
import proto2 as P             # spectral salience

def medfilt(m, k=5):
    out = np.empty(m.size); r = k // 2
    for i in range(m.size):
        v = m[max(0, i - r):min(m.size, i + r + 1)]
        v = v[~np.isnan(v)]
        out[i] = np.median(v) if v.size else np.nan
    return out

def segment(midi, tol=0.9, min_frames=3, merge=0.9):
    if midi is None or np.all(np.isnan(midi)): return []
    runs = []
    cur = None
    for v in midi:
        if np.isnan(v):
            if cur: runs.append(cur); cur = None
            continue
        if cur is None: cur = [v]
        elif abs(v - float(np.median(cur))) <= tol: cur.append(v)
        else: runs.append(cur); cur = [v]
    if cur: runs.append(cur)
    notes = [[float(np.median(r_)), len(r_)] for r_ in runs if len(r_) >= min_frames]
    merged = []
    for n in notes:
        if merged and abs(n[0] - merged[-1][0]) <= merge:
            w = merged[-1][1] + n[1]
            merged[-1] = [(merged[-1][0] * merged[-1][1] + n[0] * n[1]) / w, w]
        else:
            merged.append(n)
    return merged

def intervals(notes):
    v = [n[0] for n in notes]
    return [round(v[0])] + [round(v[i] - v[i - 1]) for i in range(1, len(v))]

def align(a, b, gap=2.2, skip=0.35, cap=5.0):
    la, lb = len(a), len(b)
    if la < 2 or lb < 2: return 0.0
    D = np.full((la + 1, lb + 1), 1e18)
    D[0, 0] = 0.0
    for i in range(1, la + 1): D[i, 0] = i * gap
    for j in range(1, lb + 1): D[0, j] = 0.0
    for i in range(1, la + 1):
        for j in range(1, lb + 1):
            c = min(abs(a[i - 1] - b[j - 1]), cap)
            D[i, j] = min(D[i-1, j-1] + c, D[i-1, j] + gap, D[i, j-1] + gap * skip)
    cost = float(np.min(D[la, :]))
    return float(np.exp(-cost / (la * 2.4)))

def hum_notes(pcm, hop=256):
    x = np.asarray(pcm, dtype=np.float64) / 32768.0 if np.max(np.abs(pcm)) > 4 else np.asarray(pcm, dtype=np.float64)
    m = Y.clean_trace(x, hop=hop, min_hz=80, max_hz=1200, prior_w=0.05, win=1536)
    return segment(medfilt(m, 9), tol=1.2, min_frames=8, merge=1.2)

def song_notes(sig, hop=1024):
    m, _ = P.trace2(sig, hop=hop, flux=0.0, fmin=110.0, whiten_spec=True)
    return segment(medfilt(P.viterbi_midi(medfilt(m, 5)), 3)) if hasattr(P, 'viterbi_midi') else segment(medfilt(vit(m), 3))

def vit(m, jump=1.4, hold=0.55):
    grid = np.unique(np.round(m[~np.isnan(m)] * 2) / 2)
    if grid.size == 0: return m
    n = m.size
    prev = np.zeros(grid.size); back = np.zeros((n, grid.size), dtype=np.int16)
    for i in range(n):
        obs = np.full(grid.size, 4.0)
        if not np.isnan(m[i]):
            obs[np.abs(grid - m[i]).argmin()] = 0.0
        cost = np.empty(grid.size)
        for g in range(grid.size):
            d = np.abs(grid - grid[g]); t = jump * d - hold * (d == 0)
            tot = prev + t
            back[i, g] = int(np.argmin(tot)); cost[g] = tot[back[i, g]] + obs[g]
        prev = cost
    path = [int(np.argmin(prev))]
    for i in range(n - 1, 0, -1): path.append(int(back[i, path[-1]]))
    return grid[np.array(path[::-1])]

MELODIES = P.MELODIES
bank = []
for i, mel in enumerate(MELODIES):
    notes = song_notes(P.arrangement(mel, seed=i + 1))
    bank.append((i, intervals(notes), notes))
    print(f"song {i}: true {mel}  -> {[round(v) for v,_ in notes]}")

print()
for target in (0, 2, 4, 6):
    for tr, tempo, oct_ in ((0,1.0,0),(5,1.12,-1),(-7,0.9,1),(3,1.0,1)):
        hn = hum_notes(P.sung(MELODIES[target], transpose=tr, tempo=tempo, octave=oct_))
        hi = intervals(hn)
        row = [align(hi, s[1]) for s in bank]
        best = int(np.argmax(row))
        margin = sorted(row)[-1] - (sorted(row)[-2] if len(row) > 1 else 0)
        ok = bank[best][0] == target
        print(f"hum song{target} tr{tr:+d} t{tempo} o{oct_:+d}: notes={[round(v) for v,_ in hn][:10]} scores=" +
              " ".join(f"{v:.2f}" for v in row) + f" {'OK' if ok else 'MISS'} margin={margin:.2f}")

print("\n== negative control: melodies nobody has ==")
OUTSIDERS = [[60,63,67,70,67,63,60,58],[72,70,68,65,63,65,68,70],[55,62,57,64,59,66,61,68]]
for mel in OUTSIDERS:
    for tr, tempo, oct_ in ((0,1.0,0),(4,1.1,-1)):
        hn = hum_notes(P.sung(mel, transpose=tr, tempo=tempo, octave=oct_))
        hi = intervals(hn)
        row = [align(hi, s[1]) for s in bank]
        print(f" outsider tr{tr:+d}: max={max(row):.2f} scores=" + " ".join(f"{v:.2f}" for v in row))
