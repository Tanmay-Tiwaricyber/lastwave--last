"""Note-level interval matching: segment each contour into notes, compare interval sequences."""
import numpy as np, proto2 as P

def viterbi_midi(midi, jump_cost=1.4, hold_bonus=0.55):
    """One pass of pitch-continuity smoothing over a semitone grid."""
    grid = np.unique(np.round(midi[~np.isnan(midi)] * 2) / 2)
    if grid.size == 0: return midi
    n = midi.size
    best = np.full(n, np.nan)
    prev = np.zeros(grid.size)
    back = np.zeros((n, grid.size), dtype=np.int16)
    for i in range(n):
        obs = np.full(grid.size, 4.0)
        if not np.isnan(midi[i]):
            k = np.abs(grid - midi[i]).argmin()
            obs[k] = 0.0
        cost = np.empty(grid.size)
        arg = np.zeros(grid.size, dtype=np.int16)
        for g in range(grid.size):
            d = np.abs(grid - grid[g])
            trans = jump_cost * d - hold_bonus * (d == 0)
            tot = prev + trans
            arg[g] = int(np.argmin(tot)); cost[g] = tot[arg[g]] + obs[g]
        prev = cost; back[i] = arg
    path = [int(np.argmin(prev))]
    for i in range(n - 1, 0, -1):
        path.append(int(back[i][path[-1]]))
    path.reverse()
    return np.where(np.isnan(midi), np.nan, grid[np.array(path)])

def segment(midi, tolerance=0.85, min_frames=2):
    """Turn a frame contour into (mean note, length in frames)."""
    if midi is None or np.all(np.isnan(midi)): return []
    notes = []
    cur = [None]
    for v in midi:
        if np.isnan(v):
            if cur[0] is not None: notes.append(cur[0]); cur = [None]
            continue
        if cur[0] is None: cur[0] = [v]
        elif abs(v - float(np.median(cur[0]))) <= tolerance: cur[0].append(v)
        else:
            notes.append(cur[0]); cur[0] = [v]
    if cur[0] is not None: notes.append(cur[0])
    return [(float(np.median(n)), len(n)) for n in notes if len(n) >= min_frames]

def intervals(notes):
    vals = [n[0] for n in notes]
    return [vals[0]] + [round(vals[i] - vals[i - 1]) for i in range(1, len(vals))]

def align(a, b, gap=2.5, max_note_cost=4.0):
    """Needleman-Wunsch over interval sequences; returns a 0..1 similarity."""
    la, lb = len(a), len(b)
    if la == 0 or lb == 0: return 0.0, 0
    D = np.full((la + 1, lb + 1), 1e9)
    D[0, 0] = 0.0
    for i in range(1, la + 1): D[i, 0] = D[i - 1, 0] + gap
    for j in range(1, lb + 1): D[0, j] = D[0, j - 1] + gap * 0.4   # free-ish skip of song frames
    for i in range(1, la + 1):
        for j in range(1, lb + 1):
            c = min(abs(a[i - 1] - b[j - 1]), max_note_cost)
            D[i, j] = min(D[i - 1, j - 1] + c, D[i - 1, j] + gap, D[i, j - 1] + gap * 0.4)
    # allow the tail of the song to be skipped for free
    cost = min(D[la, j] + gap * 0.0 for j in range(1, lb + 1))
    sim = float(np.exp(-cost / (la * 2.6)))
    return sim, int(np.argmin(D[la]))

def medfilt(m, k=5):
    if m.size == 0: return m
    out = np.empty(m.size)
    r = k // 2
    for i in range(m.size):
        v = m[max(0, i - r):min(m.size, i + r + 1)]
        v = v[~np.isnan(v)]
        out[i] = np.median(v) if v.size else np.nan
    return out


def contour_sig(midi, radius=5):
    m = viterbi_midi(medfilt(midi, radius))
    notes = segment(medfilt(m, 3))
    return notes

hop_s, hop_h = 1024, 256
bank = []
for i, mel in enumerate(P.MELODIES):
    x = P.arrangement(mel, seed=i + 1)
    m, _ = P.trace2(x, hop=hop_s, flux=0.0, fmin=110.0, whiten_spec=True)
    notes = contour_sig(m)
    bank.append((i, notes))
    print(f"song {i}: true {[int(v) for v in mel]}  tracked {[round(v) for v,_ in notes]}")

print()
for tr, tempo, oct_ in ((0,1.0,0),(5,1.12,-1),(-7,0.9,1),(3,1.0,1)):
    hm, _ = P.trace2(P.sung(P.MELODIES[4], transpose=tr, tempo=tempo, octave=oct_), hop=hop_h, flux=0.0, fmin=90.0, whiten_spec=False)
    hnotes = contour_sig(hm)
    h_int = intervals(hnotes)
    row = []
    for j, notes in bank:
        s, _ = align(h_int, intervals(notes))
        row.append(s)
    order = int(np.argmax(row))
    print(f"hum song4 (tr{tr:+d},t{tempo},o{oct_:+d}) notes={[round(v) for v,_ in hnotes]}")
    print("   " + " ".join(f"{v:.2f}" for v in row) + f"   best=song{bank[order][0]} {'OK' if bank[order][0]==4 else 'MISS'}")
