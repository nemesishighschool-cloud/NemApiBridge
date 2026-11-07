FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_AVD_HOME=/opt/android-avd \
    DISPLAY=:0

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libasound2 \
    libdbus-1-3 \
    libgl1 \
    libnss3 \
    libpulse0 \
    libx11-6 \
    libx11-xcb1 \
    libxcb1 \
    libxcomposite1 \
    libxcursor1 \
    libxi6 \
    libxkbcommon0 \
    libxkbcommon-x11-0 \
    libxrandr2 \
    libxrender1 \
    libxtst6 \
    openjdk-17-jre-headless \
    novnc \
    patch \
    unzip \
    websockify \
    x11vnc \
    xvfb \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /usr/local/bin/android-emulator-entrypoint
COPY novnc-compat.patch /tmp/novnc-compat.patch
RUN chmod 0755 /usr/local/bin/android-emulator-entrypoint
RUN patch -d /usr/share/novnc -p0 < /tmp/novnc-compat.patch \
    && rm /tmp/novnc-compat.patch

EXPOSE 6080
ENTRYPOINT ["/usr/local/bin/android-emulator-entrypoint"]
