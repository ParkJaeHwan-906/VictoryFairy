# VictoryFairy_AI/runner/Dockerfile
# 빌드 컨텍스트 = VictoryFairy_AI/ :
#   docker build -f runner/Dockerfile -t victoryfairy-quiz-runner .
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends awscli \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app/VictoryFairy_AI
COPY runner/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY question-gen/ question-gen/
COPY runner/runner/ runner/
COPY runner/entrypoint-quiz.sh /usr/local/bin/entrypoint-quiz.sh
RUN chmod +x /usr/local/bin/entrypoint-quiz.sh
ENV PYTHONPATH=/app/VictoryFairy_AI
ENTRYPOINT ["/usr/local/bin/entrypoint-quiz.sh"]
