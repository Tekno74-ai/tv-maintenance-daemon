FROM python:3.11-slim-bookworm

LABEL maintainer="Tekno74-ai"
LABEL description="Android TV 24/7 background maintenance daemon and AOT speed compiler"

RUN apt-get update && apt-get install -y --no-install-recommends \
    adb \
    cron \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY . /app

# Setup default system crontab for 04:30 nightly maintenance (/etc/cron.d uses 6-field format with user)
RUN printf "30 4 * * * root /usr/local/bin/python /app/core/optimizer.py --wake >> /app/logs/tv_cron.log 2>&1\n" > /etc/cron.d/tv-maintenance && \
    chmod 0644 /etc/cron.d/tv-maintenance && \
    mkdir -p /app/logs /data

ENV PYTHONUNBUFFERED=1

CMD ["sh", "-c", "cron && python /app/core/daemon.py"]
