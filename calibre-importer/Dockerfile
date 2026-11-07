ARG CALIBRE_BASE_IMAGE=lscr.io/linuxserver/calibre:latest
FROM ${CALIBRE_BASE_IMAGE}

WORKDIR /app

COPY app.py /app/app.py
COPY grimmory_repair.py /app/grimmory_repair.py
COPY consolidation.py /app/consolidation.py
COPY templates /app/templates
COPY static /app/static

EXPOSE 8090

ENTRYPOINT ["python3", "/app/app.py"]
