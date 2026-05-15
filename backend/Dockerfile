# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# System deps required by chromadb (sqlite is bundled, but build tools help for wheels)
# ffmpeg: WAV → Opus Conversion für TTS-Antworten (Quelle: melotts-Container)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential \
      curl \
      ffmpeg \
 && rm -rf /var/lib/apt/lists/*

# TTS-Synthese läuft im separaten melotts-Container (port 8200) — kein lokales Modell mehr.

WORKDIR /app

# Install Python deps first for better layer caching
COPY requirements.txt ./
RUN pip install -r requirements.txt

# App source
COPY app ./app

# Non-root user
RUN useradd --create-home --shell /usr/sbin/nologin --uid 10001 app \
 && mkdir -p /vault /data/chroma \
 && chown -R app:app /app /vault /data
USER app

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
