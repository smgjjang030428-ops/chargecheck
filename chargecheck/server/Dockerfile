FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 8000
# 호스팅(Render 등)이 PORT를 정해 주면 그 포트로 연다. HTTPS는 호스팅 앞단이 처리한다.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --proxy-headers --forwarded-allow-ips='*'"]
