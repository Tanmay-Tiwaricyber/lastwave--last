"""One code path? Track the sung line with the same spectral salience + Viterbi used for mixes."""
import numpy as np, proto2 as P, proto6 as S

def spec_trace(pcm, hop, fmin, fmax, whiten, prior_center, prior_width, min_clarity=0.25):
    x = np.asarray(pcm, dtype=np.float64)
    if np.max(np.abs(x)) > 4: x = x / 32768.0
    m, _ = P.trace2(x, hop=hop, fmin=fmin, fmax=fmax, flux=0.0, whiten_spec=whiten,
                    prior_center=prior_center, prior_width=prior_width, min_clarity=min_clarity)
    return m

TRUE = {0:[64,64,67,69,67,64,62,60], 2:[69,67,64,62,60,62,64,67], 4:[60,64,67,72,69,67,64,60], 6:[55,59,62,64,62,59,55,53]}
print("== hum contour, spectral vs yin ==")
for target in (0, 4):
    for tr, tempo, oct_ in ((0,1.0,0),(5,1.12,-1),(-7,0.9,1)):
        pcm = P.sung(P.MELODIES[target], transpose=tr, tempo=tempo, octave=oct_)
        a = S.segment(S.medfilt(spec_trace(pcm, 512, 90.0, 1300.0, False, 300.0, 1.6), 9), tol=1.2, min_frames=4, merge=1.2)
        b = S.segment(S.medfilt(P.vit(S.medfilt(spec_trace(pcm, 512, 90.0, 1300.0, False, 300.0, 1.6), 9), ) if False else S.medfilt(spec_trace(pcm, 512, 90.0, 1300.0, False, 300.0, 1.6), 9), 9), tol=1.2, min_frames=4, merge=1.2)
        want = [m + tr + 12 * oct_ for m in TRUE[target]]
        print(f"song{target} tr{tr:+d} t{tempo} o{oct_:+d}: spec={[round(v) for v,_ in a]}")
        print(f"    want {want}")

print("\n== full ranking with a single spectral path for both sides ==")
bank = []
for i, mel in enumerate(P.MELODIES):
    m = spec_trace(P.arrangement(mel, seed=i + 1), 1024, 110.0, 1300.0, True, 320.0, 1.35)
    notes = S.segment(S.medfilt(S.vit(S.medfilt(m, 5)), 5), tol=1.2, min_frames=2, merge=1.2)
    bank.append((i, S.intervals(notes)))
    print(f"song{i}: {[round(v) for v,_ in notes]}")
wins = 0; total = 0
for target in (0, 2, 4, 6):
    for tr, tempo, oct_ in ((0,1.0,0),(5,1.12,-1),(-7,0.9,1),(3,1.0,1)):
        m = spec_trace(P.sung(P.MELODIES[target], transpose=tr, tempo=tempo, octave=oct_), 512, 90.0, 1300.0, False, 300.0, 1.6)
        notes = S.segment(S.medfilt(m, 9), tol=1.2, min_frames=4, merge=1.2)
        hi = S.intervals(notes)
        row = [S.align(hi, s[1]) for s in bank]
        best = int(np.argmax(row)); total += 1; wins += int(bank[best][0] == target)
        print(f"hum{target} tr{tr:+d}t{tempo}o{oct_:+d}: " + " ".join(f"{v:.2f}" for v in row) + f" {'OK' if bank[best][0]==target else 'MISS'}")
print(f"\n{wins}/{total} top-1")
