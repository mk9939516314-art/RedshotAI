import asyncio, json, os, subprocess, sys, time, wave, re
import numpy as np

PHRASES = [
    "रेडशॉट मम्मी को कॉल लगा",
    "सुन रेडशॉट क्रोम खोल",
    "रेडशॉट गाना प्ले कर",
    "भाई मुझे कुछ दिक्कत हो गई है रेडशॉट",
    "रेडशॉट व्हाट्सएप खोल",
    "रेडशॉट टॉर्च ऑन कर",
    "रेडशॉट आवाज़ कम कर",
    "रेडशॉट पाँच मिनट का टाइमर लगा",
    "रेडशॉट आज मौसम कैसा है",
    "रेडशॉट यूट्यूब खोल",
    "रेडशॉट पापा को फोन लगा",
    "रेडशॉट तुम ही हो गाना चला",
    "रेडशॉट मैं मुसीबत में हूँ",
    "Redshot open Chrome",
    "Redshot call mummy",
    "Redshot play a song",
]
VOICES = ["hi-IN-MadhurNeural", "hi-IN-SwaraNeural"]

DEV = {c: v for c, v in {
 'क':'k','ख':'k','ग':'g','घ':'g','च':'k','छ':'k','ज':'j','झ':'j','ट':'t','ठ':'t','ड':'d','ढ':'d','त':'t','थ':'t','द':'d','ध':'d',
 'न':'n','प':'p','फ':'p','ब':'b','भ':'b','म':'m','य':'y','र':'r','ल':'l','व':'v','श':'s','ष':'s','स':'s','ं':'n','ँ':'n','ण':'n'}.items()}
def skel(s):
    out = ""
    for ch in s.lower():
        c = DEV.get(ch)
        if c is None:
            c = {'c':'k','q':'k','k':'k','w':'v','f':'p','z':'j','x':'k'}.get(ch)
            if c is None and ch in "bdgjlmnprstvy": c = ch
        if c and (not out or out[-1] != c): out += c
    return out
def dist(a, b):
    d = list(range(len(b)+1))
    for i in range(1, len(a)+1):
        prev, d[0] = d[0], i
        for j in range(1, len(b)+1):
            tmp = d[j]
            d[j] = min(d[j]+1, d[j-1]+1, prev + (a[i-1] != b[j-1]))
            prev = tmp
    return d[len(b)]
def wake(text):
    ws = [w for w in re.split(r"[\s,.!?।]+", text.lower()) if w]
    def m(sk): return 4 <= len(sk) <= 6 and dist(sk, "rdst") <= 1
    for i, w in enumerate(ws):
        if m(skel(w)): return True
        if i+1 < len(ws) and m(skel(w+ws[i+1])): return True
    return False

async def synth():
    import edge_tts
    os.makedirs("audio", exist_ok=True)
    files = []
    for vi, v in enumerate(VOICES):
        for pi, p in enumerate(PHRASES):
            if p.isascii() and vi == 1 and False: continue
            voice = v if not p.isascii() else ("en-IN-PrabhatNeural" if vi == 0 else "en-IN-NeerjaNeural")
            mp3 = f"audio/{vi}_{pi}.mp3"; wav = f"audio/{vi}_{pi}.wav"
            await edge_tts.Communicate(p, voice).save(mp3)
            subprocess.run(["ffmpeg","-y","-loglevel","error","-i",mp3,"-ar","16000","-ac","1",wav], check=True)
            files.append((wav, p))
    return files

def read(wav):
    with wave.open(wav) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)

def noisy(x, snr_db=8):
    rng = np.random.default_rng(1)
    sp = np.sqrt(np.mean(x.astype(np.float64)**2)) + 1e-9
    n = rng.normal(0, sp / (10**(snr_db/20)), len(x))
    return np.clip(x + n, -32768, 32767).astype(np.int16)

def vosk_run(model, pcm, grammar=None):
    from vosk import Recognizer
    r = Recognizer(model, 16000, grammar) if grammar else Recognizer(model, 16000)
    b = pcm.tobytes()
    for i in range(0, len(b), 6400): r.AcceptWaveform(b[i:i+6400])
    return json.loads(r.FinalResult()).get("text", "")

def main():
    files = asyncio.run(synth())
    from vosk import Model, SetLogLevel
    SetLogLevel(-1)
    vm = Model("model-hi")
    from faster_whisper import WhisperModel
    wms = {n: WhisperModel(n, device="cpu", compute_type="int8") for n in ["tiny", "base", "small"]}
    stats = {}
    def rec(name, ok, dt):
        s = stats.setdefault(name, [0, 0, 0.0]); s[0] += ok; s[1] += 1; s[2] += dt
    for wav, p in files:
        clean = read(wav)
        for cond, pcm in (("clean", clean), ("noisy", noisy(clean))):
            outs = {}
            t = time.time(); outs["vosk"] = vosk_run(vm, pcm); dt_v = time.time() - t
            rec(f"vosk/{cond}", wake(outs["vosk"]), dt_v)
            tmp = "tmp.wav"
            with wave.open(tmp, "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm.tobytes())
            for n, wm in wms.items():
                t = time.time()
                segs, _ = wm.transcribe(tmp, language="hi", beam_size=1)
                outs[n] = " ".join(s.text for s in segs).strip()
                rec(f"whisper-{n}/{cond}", wake(outs[n]), time.time() - t)
            print(f"[{cond}] {p}")
            for k, v in outs.items(): print(f"    {k:8s} wake={wake(v)!s:5s} {v}")
    print("\n==== wake-word hit rate / avg seconds ====")
    for k, (ok, n, t) in sorted(stats.items()):
        print(f"{k:22s} {ok}/{n}  {t/n:.2f}s")

main()
