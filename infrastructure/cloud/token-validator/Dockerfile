FROM python:3.12-alpine

WORKDIR /app

RUN pip install --no-cache-dir \
    opentelemetry-api \
    opentelemetry-sdk \
    opentelemetry-exporter-otlp-proto-grpc \
    opentelemetry-instrumentation

COPY validator.py .

EXPOSE 4182

CMD ["python3", "validator.py"]

