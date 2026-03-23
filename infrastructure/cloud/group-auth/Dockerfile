FROM python:3.12-alpine

RUN pip install --no-cache-dir pyyaml

WORKDIR /app
COPY server.py .

EXPOSE 4183

CMD ["python3", "server.py"]

