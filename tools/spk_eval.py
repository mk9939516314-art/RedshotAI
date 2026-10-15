import json, os, subprocess, wave
import numpy as np
from vosk import Model, KaldiRecognizer, SpkModel, SetLogLevel
SetLogLevel(-1)

SENT = [
 "Aaj mausam bahut achha hai main apne ghar se baahar ghoomne ja raha hoon",
 "Redshot mummy ko call laga aur phir mere liye ek gaana chala dena",
 "Kal subah saat baje mujhe uthana kyunki class jaldi shuru hoti hai",
 "Open YouTube and search for the latest news about technology and science",
 "Mera naam Mayank hai aur main Jammu mein padhai karta hoon bahut mehnat se",
 "Tum mujhe batao ki aaj ke din mujhe kaun kaun se kaam karne chahiye",
 "Please turn on the torch and set an alarm for six in the morning tomorrow",
 "Chrome kholo aur wahan par kuch naya dekho jo mere kaam aa sake",
]
SPEAKERS = {
 "gtts_en": ("gtts", "en"), "gtts_hi": ("gtts", "hi"),
 "es_m1": ("espeak", "en+m1"), "es_m3": ("espeak", "en+m3"), "es_m7": ("espeak", "en+m7"),
 "es_f2": ("espeak", "en+f2"), "es_f4": ("espeak", "en+f4"), "es_hi": ("espeak", "hi"),
}

def tts(text, path, kind, voice):
    if kind == "gtts":
        from gtts import gTTS
        lang = "en" if voice == "en" else "hi"
        gTTS(text, lang=lang, tld="co.in" if lang == "en" else "com").save("t.mp3")
        subprocess.run(["ffmpeg","-y","-loglevel","error","-i","t.mp3","-ar","16000","-ac","1",path], check=True)
    else:
        subprocess.run(["espeak-ng","-v",voice,"-s","155","-w","t.wav",text], check=True)
        subprocess.run(["ffmpeg","-y","-loglevel","error","-i","t.wav","-ar","16000","-ac","1",path], check=True)

def read(p):
    with wave.open(p) as w: return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)

def noisy(x, snr):
    rng = np.random.default_rng(3)
    sp = np.sqrt(np.mean(x.astype(np.float64)**2)) + 1e-9
    return np.clip(x + rng.normal(0, sp/(10**(snr/20)), len(x)), -32768, 32767).astype(np.int16)

model = Model("model-hi"); spk = SpkModel("model-spk")

def embed(pcm):
    r = KaldiRecognizer(model, 16000); r.SetSpkModel(spk)
    b = pcm.tobytes(); acc = None; frames = 0
    def take(j):
        nonlocal acc, frames
        d = json.loads(j)
        if "spk" in d and d.get("spk_frames", 0) > 0:
            v = np.array(d["spk"]); v = v / np.linalg.norm(v)
            acc = v * d["spk_frames"] if acc is None else acc + v * d["spk_frames"]
            frames += d["spk_frames"]
    for i in range(0, len(b), 6400):
        if r.AcceptWaveform(b[i:i+6400]): take(r.Result())
    take(r.FinalResult())
    return (acc, frames)

def cd(a, b): return 1 - float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

os.makedirs("a", exist_ok=True)
E = {}
for name, (kind, voice) in SPEAKERS.items():
    E[name] = []
    for i, s in enumerate(SENT):
        f = f"a/{name}_{i}.wav"; tts(s, f, kind, voice)
        pcm = read(f)
        E[name].append((embed(pcm), embed(noisy(pcm, 15))))
        print(name, i, "frames", E[name][-1][0][1], flush=True)

print("\n=== distances: enroll on sentences 0-4 (mean), test on 5-7 ===")
means = {n: sum(e[0][0] for e in E[n][:5]) for n in E}
intra = {n: max(cd(means[n], e[0][0]) for e in E[n][:5]) for n in E}
for n in E:
    same = [cd(means[n], e[0][0]) for e in E[n][5:]]
    same_noisy = [cd(means[n], e[1][0]) for e in E[n][5:]]
    others = [cd(means[n], e[0][0]) for m in E if m != n for e in E[m][5:]]
    print(f"{n}: maxIntra={intra[n]:.2f} same={np.round(same,2)} same_noisy15dB={np.round(same_noisy,2)} other_min={min(others):.2f} other_mean={np.mean(others):.2f}")
