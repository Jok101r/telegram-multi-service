import io
import os
import logging

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
from diffusers import StableDiffusionPipeline, LCMScheduler
from deep_translator import GoogleTranslator
import torch

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Dreamshaper 8 + LCM-LoRA: Dreamshaper 8 quality at LCM speed (4-8 steps).
# Better than a standalone LCM model because the base is newer and higher quality.
MODEL_ID = os.getenv("IMAGE_MODEL", "Lykon/dreamshaper-8")
LORA_ID = os.getenv("IMAGE_LORA", "latent-consistency/lcm-lora-sdv1-5")
DEVICE = "cpu"
INFERENCE_STEPS = int(os.getenv("IMAGE_STEPS", "8"))
GUIDANCE_SCALE = float(os.getenv("IMAGE_GUIDANCE_SCALE", "1.5"))
IMAGE_WIDTH = int(os.getenv("IMAGE_WIDTH", "512"))
IMAGE_HEIGHT = int(os.getenv("IMAGE_HEIGHT", "512"))

DEFAULT_NEGATIVE_PROMPT = (
    "ugly, blurry, low quality, distorted, deformed, bad anatomy, "
    "bad hands, extra fingers, watermark, signature, text, cropped, "
    "worst quality, low resolution, jpeg artifacts"
)

pipeline: StableDiffusionPipeline | None = None


def load_model() -> StableDiffusionPipeline:
    logger.info("Loading base model %s ...", MODEL_ID)
    pipe = StableDiffusionPipeline.from_pretrained(
        MODEL_ID,
        torch_dtype=torch.float32,
        safety_checker=None,
        requires_safety_checker=False,
    )
    pipe.scheduler = LCMScheduler.from_config(pipe.scheduler.config)
    logger.info("Loading LCM-LoRA %s ...", LORA_ID)
    pipe.load_lora_weights(LORA_ID)
    pipe.fuse_lora()
    pipe = pipe.to(DEVICE)
    pipe.enable_attention_slicing()
    logger.info("Model loaded successfully.")
    return pipe


app = FastAPI(title="image-gen-service")


@app.on_event("startup")
def startup() -> None:
    global pipeline
    pipeline = load_model()


class GenerateRequest(BaseModel):
    prompt: str
    steps: int = INFERENCE_STEPS
    width: int = IMAGE_WIDTH
    height: int = IMAGE_HEIGHT


@app.get("/health")
def health():
    return {
        "status": "ok" if pipeline is not None else "loading",
        "model": MODEL_ID,
        "lora": LORA_ID,
        "device": DEVICE,
    }


def translate_to_english(text: str) -> str:
    try:
        translated = GoogleTranslator(source="auto", target="en").translate(text)
        if translated and translated != text:
            logger.info("Translated prompt: %r -> %r", text, translated)
        return translated or text
    except Exception as e:
        logger.warning("Translation failed (%s), using original prompt", e)
        return text


@app.post("/generate")
def generate(req: GenerateRequest):
    if pipeline is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")

    prompt_en = translate_to_english(req.prompt)

    logger.info("Generating image: prompt=%r, steps=%d, size=%dx%d",
                prompt_en, req.steps, req.width, req.height)

    result = pipeline(
        prompt=prompt_en,
        negative_prompt=DEFAULT_NEGATIVE_PROMPT,
        num_inference_steps=req.steps,
        guidance_scale=GUIDANCE_SCALE,
        width=req.width,
        height=req.height,
    )
    image = result.images[0]

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    buf.seek(0)

    logger.info("Image generated successfully.")
    return Response(content=buf.read(), media_type="image/png")
