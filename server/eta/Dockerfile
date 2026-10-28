# What the arrival model service needs besides Python, at the versions the lab built the models with; LightGBM (the
# train and light-rail models) needs OpenMP's runtime. The code itself is mounted, as live.py's is.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir polars==2.0.0 numpy==2.5.4 lightgbm==4.7.0
