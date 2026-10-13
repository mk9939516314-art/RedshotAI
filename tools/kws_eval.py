import json, os, subprocess, wave, re
import numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)

POS = [
    "रेडशॉट मम्मी को कॉल लगा", "सुन रेडशॉट क्रोम खोल", "रेडशॉट गाना प्ले कर",
    "भाई मुझे कुछ दिक्कत हो गई है रेडशॉट", "रेडशॉट व्हाट्सएप खोल", "रेडशॉट टॉर्च ऑन कर",
    "रेडशॉट आवाज़ कम कर", "रेडशॉट पाँच मिनट का टाइमर लगा", "रेडशॉट आज मौसम कैसा है",
    "रेडशॉट यूट्यूब खोल", "रेडशॉट पापा को फोन लगा", "रेडशॉट तुम ही हो गाना चला",
    "रेडशॉट मैं मुसीबत में हूँ", "रेड शॉट", "रेडशॉट", "अरे रेडशॉट सुन",
    "Redshot open Chrome", "Redshot call mummy", "Redshot play a song", "hey Redshot", "Redshot",
]
NEG = [
    "मम्मी को फोन लगा", "क्रोम खोल", "आज बहुत गर्मी है", "वो शॉट बहुत अच्छा था", "रेड कलर की शर्ट पहन ले",
    "रेड लाइट पर रुक जा", "कल कॉलेज जाना है", "भाई चाय पीने चलें", "पापा को बोल देना कि मैं आ रहा हूँ",
    "यार ये गाना बहुत अच्छा है", "तुझे पता है वो क्या बोला", "खाना खा लिया क्या", "मैं थोड़ी देर में आता हूँ",
    "open the door please", "call me later", "that was a great shot", "the red car is fast", "what time is the class",
    "मुझे रेस्ट करना है", "बस रुक जा यार",
]
GR = {
  "en": ('model-en', '["red shot", "redshot", "[unk]"]'),
  "hi": ('model-hi', '["रेड शॉट", "रेडशॉट", "रेड", "शॉट", "[unk]"]'),
}

def tts(text, path, voice):
    lang = "en" if text.isascii() else "hi"
    if voice == "gtts":
        from gtts import gTTS
        gTTS(text, lang=lang).save("t.mp3")
        subprocess.run(["ffmpeg","-y","-loglevel","error","-i","t.mp3","-ar","16000","-ac","1",path], check=True)
    else:
        subprocess.run(["espeak-ng","-v",("en" if lang=="en" else "hi"),"-s","150","-w","t.wav",text], check=True)
        subprocess.run(["ffmpeg","-y","-loglevel","error","-i","t.wav","-ar","16000","-ac","1",path], check=True)

def read(p):
    with wave.open(p) as w: return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)

def noisy(x, snr):
    rng = np.random.default_rng(2)
    sp = np.sqrt(np.mean(x.astype(np.float64)**2)) + 1e-9
    return np.clip(x + rng.normal(0, sp/(10**(snr/20)), len(x)), -32768, 32767).astype(np.int16)

def run(model, grammar, pcm):
    r = KaldiRecognizer(model, 16000, grammar)
    b = pcm.tobytes()
    for i in range(0, len(b), 6400): r.AcceptWaveform(b[i:i+6400])
    return json.loads(r.FinalResult()).get("text","")

models = {k: Model(v[0]) for k, v in GR.items()}
os.makedirs("a", exist_ok=True)
items = []
for voice in ["gtts", "espeak"]:
    for i, p in enumerate(POS): 
        f = f"a/{voice}_p{i}.wav"; tts(p, f, voice); items.append((True, p, voice, read(f)))
    for i, p in enumerate(NEG):
        f = f"a/{voice}_n{i}.wav"; tts(p, f, voice); items.append((False, p, voice, read(f)))

def hit(k, text):
    t = text.replace(" ", "")
    if k == "en": return "redshot" in t
    return "रेडशॉट" in t or ("रेड" in text and "शॉट" in text)

rows = {}
for pos, p, voice, pcm in items:
    for cond, x in (("clean", pcm), ("snr15", noisy(pcm, 15)), ("snr8", noisy(pcm, 8))):
        res = {k: run(models[k], GR[k][1], x) for k in models}
        h = {k: hit(k, res[k]) for k in res}
        h["either"] = h["en"] or h["hi"]
        for k, v in h.items():
            s = rows.setdefault((pos, cond, k), [0, 0]); s[0] += v; s[1] += 1
        if cond == "clean" and voice == "gtts":
            print(("POS" if pos else "NEG"), p, "| en:", res["en"], "| hi:", res["hi"])
print("\n==== wake spotting (hits / total) ====")
for cond in ("clean", "snr15", "snr8"):
    for k in ("en", "hi", "either"):
        a = rows[(True, cond, k)]; b = rows[(False, cond, k)]
        print(f"{cond:6s} {k:7s} wake detected {a[0]}/{a[1]}   false alarms {b[0]}/{b[1]}")
