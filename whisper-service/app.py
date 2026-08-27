import os
import tempfile

from fastapi import FastAPI, UploadFile, File
from faster_whisper import WhisperModel

MODEL_SIZE = os.environ.get("WHISPER_MODEL", "small")
# "cpu" or "cuda"; int8 on CPU is the primary source of faster-whisper's speed
DEVICE = os.environ.get("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "int8" if DEVICE == "cpu" else "float16")

model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)

app = FastAPI(title="whisper-service")


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL_SIZE, "device": DEVICE, "compute_type": COMPUTE_TYPE}


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    suffix = os.path.splitext(file.filename or "")[1] or ".ogg"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=True) as tmp:
        tmp.write(await file.read())
        tmp.flush()

        segments, info = model.transcribe(tmp.name, beam_size=5)
        text = " ".join(segment.text.strip() for segment in segments).strip()

    return {"text": text, "language": info.language}
