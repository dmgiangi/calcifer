# Dockerfile for building documentation PDFs with Quarto
# Includes TinyTeX for PDF generation and Google Chrome for Mermaid diagrams

FROM ghcr.io/quarto-dev/quarto:latest

# Install TinyTeX for PDF generation
RUN quarto install tinytex

# Install Google Chrome from official repository for Mermaid diagram rendering
RUN apt-get update && apt-get install -y wget gnupg \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

# Set environment variables for Chrome
ENV CHROME_BIN=/usr/bin/google-chrome-stable
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/google-chrome-stable
ENV QUARTO_CHROMIUM=/usr/bin/google-chrome-stable
ENV QUARTO_CHROMIUM_HEADLESS_MODE=new

WORKDIR /data