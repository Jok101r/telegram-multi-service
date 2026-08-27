FROM python:3.11-slim

WORKDIR /app

# Global pip settings
ENV PIP_DEFAULT_TIMEOUT=300 \
    PIP_RETRIES=5 \
    PIP_NO_CACHE_DIR=1

# CPU-only Torch (~800 MB vs ~3 GB for the GPU version)
# Installed as a separate layer so it is cached on rebuilds
RUN pip install --progress-bar on torch \
    --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .

# Remaining dependencies (diffusers, transformers, Pillow, ...)
RUN pip install --progress-bar on -r requirements.txt

COPY app.py .

# Model is downloaded on container startup and cached in the mounted volume
ENV IMAGE_MODEL=runwayml/stable-diffusion-v1-5
ENV IMAGE_STEPS=20
ENV IMAGE_WIDTH=512
ENV IMAGE_HEIGHT=512

EXPOSE 8001
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
