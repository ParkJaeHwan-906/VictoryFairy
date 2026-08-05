# Container-image Lambda (packages lxml's native bits cleanly).
# Build context = py-collector/ :  docker build -f deploy/lambda/Dockerfile -t kbo-collector .
FROM public.ecr.aws/lambda/python:3.12

# runtime deps (boto3 is already in the Lambda base image)
COPY deploy/lambda/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

# app code + config
COPY kbo_collector/ ${LAMBDA_TASK_ROOT}/kbo_collector/
COPY config/ ${LAMBDA_TASK_ROOT}/config/
COPY deploy/lambda/handler.py ${LAMBDA_TASK_ROOT}/handler.py

CMD ["handler.handler"]
